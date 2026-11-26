package com.lois.webtoontracker;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Une recherche texte de « Découvrir » sur deux sources, page après page (Java pur, testable).
 *
 * <p>AniList est interrogée exactement comme avant ({@link Discover#browse}, pages de 30) ; MangaDex
 * ({@link MangaDex#search}, pages de 20) complète. Les deux pages N partent en même temps et chaque source
 * avance avec son propre curseur. Une source en panne, trop lente ou épuisée n'empêche pas l'autre : ses
 * résultats restent, on le signale discrètement, et on ne la relance pas d'elle-même (bouton « Réessayer »).
 */
final class MultiSearch {

    /** Une page AniList (la requête existante, inchangée). */
    interface AniListPages {
        Discover.Result fetch(int page) throws Exception;
    }

    /** Une page MangaDex à partir d'une position. */
    interface MangaDexPages {
        MangaDex.Result fetch(int offset) throws Exception;
    }

    /** Où en est une source. */
    enum State { ACTIVE, DONE, FAILED }

    /** Erreur d'une source, avec son nom (pour un message clair). */
    static final class SourceError extends Exception {
        final Discover.Source source;

        SourceError(Discover.Source source, Throwable cause) {
            super(source.label + " : " + cause.getMessage(), cause);
            this.source = source;
        }
    }

    /**
     * Délais d'attente d'une page. AniList garde ses propres délais réseau (jamais coupée plus tôt
     * qu'avant) ; MangaDex a 9 s au plus, et 3 s de plus qu'AniList une fois celle-ci arrivée : une
     * source lente ne retient pas les résultats de l'autre.
     */
    static final long ANILIST_DEADLINE_MS = 30_000, MANGADEX_DEADLINE_MS = 9000, GRACE_MS = 3000;
    /** MangaDex : 5 pages de 20 au plus par recherche (au-delà, les titres ne correspondent plus guère). */
    static final int MD_MAX_PAGES = 5;

    private static final ExecutorService NET = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "recherche");
        t.setDaemon(true);
        return t;
    });

    private final AniListPages anilist;
    private final MangaDexPages mangadex;
    private final SearchMerge merge;
    private final long mdDeadlineMs, graceMs;

    private int aniPage = 1, mdOffset, mdPages, loads;
    private State ani = State.ACTIVE, md = State.ACTIVE;
    private Exception aniError, mdError;

    MultiSearch(String query, AniListPages anilist, MangaDexPages mangadex, long mdDeadlineMs, long graceMs) {
        this.anilist = anilist;
        this.mangadex = mangadex;
        this.merge = new SearchMerge(query);
        this.mdDeadlineMs = mdDeadlineMs;
        this.graceMs = graceMs;
        if (SearchMerge.norm(query).length() < 2) md = State.DONE;   // 1 lettre : AniList suffit
    }

    /** La recherche réelle : la requête AniList existante + MangaDex, avec les mêmes critères. */
    static MultiSearch of(Discover.Query q, Discover.Profile me) {
        final Discover.Query snap = q.copy();
        return new MultiSearch(snap.search, page -> Discover.browse(snap, page, me),
                offset -> MangaDex.search(snap, offset, me), MANGADEX_DEADLINE_MS, GRACE_MS);
    }

    /**
     * La page suivante, fusionnée. À appeler hors du fil principal, une à la fois.
     *
     * @throws SourceError si toutes les sources interrogées ont échoué (rien à montrer)
     */
    SearchMerge.Page next() throws SourceError {
        loads++;
        final boolean askAni = ani == State.ACTIVE, askMd = md == State.ACTIVE;
        final int page = aniPage, offset = mdOffset;
        Future<Discover.Result> fa = askAni ? NET.submit(() -> anilist.fetch(page)) : null;
        Future<MangaDex.Result> fm = askMd ? NET.submit(() -> mangadex.fetch(offset)) : null;
        final long start = System.currentTimeMillis();

        Discover.Result ar = null;
        MangaDex.Result mr = null;
        if (fa != null) {
            try {
                ar = await(fa, start + ANILIST_DEADLINE_MS);
                aniPage++;
                ani = ar.hasNext ? State.ACTIVE : State.DONE;
                aniError = null;
            } catch (Exception e) {
                ani = State.FAILED;
                aniError = e;
            }
        }
        long mdEnd = start + mdDeadlineMs;
        if (fa != null) mdEnd = Math.min(mdEnd, System.currentTimeMillis() + graceMs);
        if (fm != null) {
            try {
                mr = await(fm, mdEnd);
                mdOffset = mr.nextOffset;
                mdPages++;
                md = mr.hasNext && mdPages < MD_MAX_PAGES ? State.ACTIVE : State.DONE;
                mdError = null;
            } catch (Exception e) {
                md = State.FAILED;
                mdError = e;
            }
        }
        boolean nothing = (!askAni || ar == null) && (!askMd || mr == null);
        if (nothing && (askAni || askMd)) {   // tout ce qu'on a demandé a échoué
            throw askAni ? new SourceError(Discover.Source.ANILIST, aniError) : new SourceError(Discover.Source.MANGADEX, mdError);
        }
        List<Discover.Media> none = Collections.emptyList();
        return merge.add(ar == null ? none : ar.items, mr == null ? none : mr.items);
    }

    /** Attend une source jusqu'à l'échéance ; au-delà, on l'abandonne pour cette page. */
    private static <T> T await(Future<T> f, long end) throws Exception {
        try {
            return f.get(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new TimeoutException("délai dépassé");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof Exception ? (Exception) c : new Exception(c);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** Encore des pages à demander ? */
    boolean hasMore() {
        return ani == State.ACTIVE || md == State.ACTIVE;
    }

    /** Une source a échoué (ses résultats manquent, « Réessayer » la relance). */
    boolean hasFailed() {
        return ani == State.FAILED || md == State.FAILED;
    }

    /** Relance les sources en échec (bouton « Réessayer »). */
    void retryFailed() {
        if (ani == State.FAILED) ani = State.ACTIVE;
        if (md == State.FAILED) md = State.ACTIVE;
    }

    int loads() {
        return loads;
    }

    State anilistState() {
        return ani;
    }

    State mangadexState() {
        return md;
    }

    /** « · MangaDex indisponible » pour la ligne d'info, ou "" si tout va bien. */
    String sourcesNote() {
        if (ani == State.FAILED && md == State.FAILED) return " · sources indisponibles";
        if (ani == State.FAILED) return " · AniList indisponible";
        if (md == State.FAILED) return " · MangaDex indisponible";
        return "";
    }

    /** Fin de liste quand une source manque : « MangaDex ne répond pas : résultats AniList seulement. » */
    String failureNote() {
        if (ani == State.FAILED && md != State.FAILED) {
            return "AniList ne répond pas : résultats MangaDex seulement.";
        }
        if (md == State.FAILED && ani != State.FAILED) {
            return MangaDex.paused() ? "MangaDex demande une pause : résultats AniList seulement."
                    : "MangaDex ne répond pas : résultats AniList seulement.";
        }
        if (md == State.FAILED) return "AniList et MangaDex ne répondent pas.";
        return null;
    }
}
