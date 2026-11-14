import assert from "node:assert/strict";
import { test } from "node:test";

import { CustomRootLoader, CUSTOM_INTERFACE_START } from "../widgets/custom/CustomRootLoader";
import { PendingInterfaceUpdates } from "../widgets/custom/PendingInterfaceUpdates";

/**
 * Defers a promise so the test can resolve it manually. Lets a test interleave another
 * set_root while a fetch is "in flight" instead of racing the event loop.
 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (err: unknown) => void } {
    let resolve!: (value: T) => void;
    let reject!: (err: unknown) => void;
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
}

function makeDefinition(groupId: number) {
    return { groupId, widgets: [{ uid: (groupId << 16) | 0, parentUid: -1 }] };
}

function makeHarness() {
    const loaded = new Set<number>();
    const installed: number[] = [];
    const fetchCalls: number[] = [];
    const applyCalls: number[] = [];
    const replayed: unknown[] = [];
    const inflight = new Map<number, (def: unknown) => void>();
    const pendingInterfaceUpdates = new PendingInterfaceUpdates();
    const loader = new CustomRootLoader({
        isGroupLoaded: (id) => loaded.has(id),
        fetchInterfaceDefinition: (groupId) => {
            fetchCalls.push(groupId);
            const { promise, resolve } = deferred<unknown>();
            inflight.set(groupId, resolve);
            return promise as Promise<any>;
        },
        setCustomInterface: (definition: any) => {
            if (!definition || typeof definition.groupId !== "number") return false;
            installed.push(definition.groupId);
            loaded.add(definition.groupId);
            return true;
        },
        handleWidgetPayload: (payload) => { replayed.push(payload); },
        pendingInterfaceUpdates,
    });
    return {
        loader, loaded, installed, fetchCalls, applyCalls, replayed, inflight, pendingInterfaceUpdates,
        apply: (payload: { groupId: number }) => () => { applyCalls.push(payload.groupId); },
        resolveFetch: (groupId: number, def: unknown) => {
            const resolver = inflight.get(groupId);
            if (!resolver) throw new Error(`no in-flight fetch for ${groupId}`);
            inflight.delete(groupId);
            resolver(def);
        },
        tick: () => new Promise<void>((res) => setTimeout(res, 0)),
    };
}

test("cache-backed welcome root takes the sync path", () => {
    const h = makeHarness();
    const ran = h.loader.load(378, h.apply({ groupId: 378 }));
    assert.equal(ran, true, "stock welcome root runs synchronously");
    assert.deepEqual(h.applyCalls, [378]);
    assert.deepEqual(h.fetchCalls, [], "no fetch for cache-backed root");
});

test("gameframe roots all take the sync path", () => {
    const h = makeHarness();
    for (const id of [161, 164, 165, 548, 601]) {
        const ran = h.loader.load(id, h.apply({ groupId: id }));
        assert.equal(ran, true, `gameframe root ${id} runs synchronously`);
    }
    assert.deepEqual(h.applyCalls, [161, 164, 165, 548, 601]);
    assert.deepEqual(h.fetchCalls, []);
});

test("unloaded custom root fetches first, then applies and flushes deferred updates", async () => {
    const h = makeHarness();
    const ran = h.loader.load(30020, h.apply({ groupId: 30020 }));
    assert.equal(ran, false, "unloaded custom root defers applySetRoot");
    assert.deepEqual(h.fetchCalls, [30020]);
    assert.deepEqual(h.applyCalls, [], "apply does not fire before fetch resolves");

    // Updates arriving while the fetch is in flight are buffered.
    assert.equal(
        h.pendingInterfaceUpdates.defer({ action: "set_text", uid: (30020 << 16) | 3, text: "Welcome" }),
        true,
        "component updates for the pending root are deferred",
    );
    assert.equal(
        h.pendingInterfaceUpdates.defer({ action: "set_text", uid: (161 << 16) | 16 }),
        false,
        "updates for other groups are not deferred",
    );

    h.resolveFetch(30020, makeDefinition(30020));
    await h.tick();
    assert.deepEqual(h.installed, [30020], "definition is installed before apply");
    assert.deepEqual(h.applyCalls, [30020], "apply runs after fetch resolves");
    assert.equal(h.replayed.length, 1, "deferred update is replayed after mount");
    assert.equal((h.replayed[0] as any).action, "set_text");
    assert.equal(h.pendingInterfaceUpdates.size, 0, "pending queue drains to zero after flush");
});

test("already-loaded custom root skips the fetch on a subsequent set_root", () => {
    const h = makeHarness();
    h.loaded.add(30020); // Simulate "fetched earlier this session".
    const ran = h.loader.load(30020, h.apply({ groupId: 30020 }));
    assert.equal(ran, true, "loaded custom root takes the sync path");
    assert.deepEqual(h.fetchCalls, []);
    assert.deepEqual(h.applyCalls, [30020]);
});

test("a second set_root during the fetch window supersedes the first (another custom root)", async () => {
    const h = makeHarness();
    h.loader.load(30020, h.apply({ groupId: 30020 }));
    const ran2 = h.loader.load(30021, h.apply({ groupId: 30021 }));
    assert.equal(ran2, false, "second unloaded custom root also defers");
    assert.deepEqual(h.fetchCalls, [30020, 30021]);
    assert.equal(h.pendingInterfaceUpdates.size, 1, "orphaned 30020 queue cancelled; 30021 pending");

    // The orphaned first fetch resolves — apply must NOT fire for 30020.
    h.resolveFetch(30020, makeDefinition(30020));
    await h.tick();
    assert.deepEqual(h.applyCalls, [], "superseded set_root result is dropped");

    // The second fetch resolves normally.
    h.resolveFetch(30021, makeDefinition(30021));
    await h.tick();
    assert.deepEqual(h.applyCalls, [30021]);
});

test("a cache-root set_root during the fetch window supersedes the pending custom one", async () => {
    const h = makeHarness();
    h.loader.load(30020, h.apply({ groupId: 30020 }));
    h.loader.load(161, h.apply({ groupId: 161 }));
    assert.deepEqual(h.applyCalls, [161], "cache root applies immediately");
    assert.equal(h.pendingInterfaceUpdates.size, 0, "pending queue for 30020 cancelled");

    h.resolveFetch(30020, makeDefinition(30020));
    await h.tick();
    assert.deepEqual(h.applyCalls, [161], "superseded 30020 result still dropped");
});

test("fetch failure logs an error and cancels the pending queue without crashing", async () => {
    const h = makeHarness();
    const originalError = console.error;
    const errors: string[] = [];
    console.error = (...args) => errors.push(args.join(" "));
    try {
        h.loader.load(30020, h.apply({ groupId: 30020 }));
        h.resolveFetch(30020, undefined);
        await h.tick();
    } finally {
        console.error = originalError;
    }
    assert.deepEqual(h.applyCalls, [], "apply is not called on fetch failure");
    assert.equal(h.pendingInterfaceUpdates.size, 0, "queue cancelled on fetch failure");
    assert.equal(errors.length, 1);
    assert.match(errors[0], /30020/);
});

test("malformed definition (setCustomInterface returns false) is handled like a fetch failure", async () => {
    const h = makeHarness();
    const originalError = console.error;
    console.error = () => { /* silence */ };
    try {
        h.loader.load(30020, h.apply({ groupId: 30020 }));
        h.resolveFetch(30020, { widgets: [] }); // missing groupId
        await h.tick();
    } finally {
        console.error = originalError;
    }
    assert.deepEqual(h.applyCalls, []);
    assert.equal(h.pendingInterfaceUpdates.size, 0);
});

test("cancel() before fetch resolves prevents the stale result from firing applySetRoot", async () => {
    // Regression: OsrsClient.resetWorld/dispose must call loader.cancel() before
    // widgetManager.clear(), so a late-arriving fetch result from before the reset
    // cannot install a definition and fire applySetRoot into a torn-down tree.
    // This test exercises the cancel() contract the integration relies on.
    const h = makeHarness();
    h.loader.load(30020, h.apply({ groupId: 30020 }));
    assert.equal(h.pendingInterfaceUpdates.size, 1, "fetch is open");
    // Simulate resetWorld / dispose: call cancel() while the fetch is still pending.
    h.loader.cancel();
    assert.equal(h.pendingInterfaceUpdates.size, 0, "cancel drops the pending queue immediately");
    // The fetch eventually resolves — late arrival after the "client was reset".
    h.resolveFetch(30020, makeDefinition(30020));
    await h.tick();
    assert.deepEqual(h.applyCalls, [], "apply does not fire after cancel");
    assert.deepEqual(h.installed, [], "definition is not installed after cancel");
});

test("explicit cancel() aborts an in-flight fetch", async () => {
    const h = makeHarness();
    h.loader.load(30020, h.apply({ groupId: 30020 }));
    assert.equal(h.pendingInterfaceUpdates.size, 1);
    h.loader.cancel();
    assert.equal(h.pendingInterfaceUpdates.size, 0);
    h.resolveFetch(30020, makeDefinition(30020));
    await h.tick();
    assert.deepEqual(h.applyCalls, []);
});

test("same-group fast path after external load cancels the still-open pending queue", async () => {
    // Scenario from the review: load(A) starts a fetch, another path installs A while
    // that fetch is still pending, a fresh set_root(A) arrives. Pre-fix, the earlier
    // pending-queue was not cancelled because the group matched the pending one —
    // the top-of-load cancel branch required a different group. Updates buffered for
    // that group would then sit in the queue forever.
    const h = makeHarness();
    h.loader.load(30020, h.apply({ groupId: 30020 }));
    assert.equal(h.pendingInterfaceUpdates.size, 1, "fetch is open; queue entry exists");
    // Buffer an update that arrived during the fetch window.
    h.pendingInterfaceUpdates.defer({ action: "set_text", uid: (30020 << 16) | 3 });

    // External path installs the definition (e.g. open_sub for the same group).
    h.loaded.add(30020);
    h.pendingInterfaceUpdates.flush(30020, () => { /* consumed by external path */ });
    // (If external flush didn't happen, the queue would still have the deferred update.)
    h.pendingInterfaceUpdates.open(30020);
    h.pendingInterfaceUpdates.defer({ action: "set_text", uid: (30020 << 16) | 3 });
    assert.equal(h.pendingInterfaceUpdates.size, 1, "queue re-opened by a subsequent in-flight state");

    // Fresh set_root for the same group — must not strand this queue.
    const ran = h.loader.load(30020, h.apply({ groupId: 30020 }));
    assert.equal(ran, true, "already-loaded custom root takes the sync path");
    assert.deepEqual(h.applyCalls, [30020]);
    assert.equal(
        h.pendingInterfaceUpdates.size, 0,
        "same-group fast path releases the still-open pending queue",
    );
});

test("rejected fetch promise is handled without unhandled rejection", async () => {
    // Real fetchInterfaceDefinition catches network/fallback failures and resolves
    // undefined, so this test exercises the loader's error contract — if a dependency
    // ever does reject (future transport, injection error), the loader must clean up
    // instead of leaking an unhandled rejection.
    const loaded = new Set<number>();
    const pendingInterfaceUpdates = new PendingInterfaceUpdates();
    const applyCalls: number[] = [];
    const errors: string[] = [];
    const originalError = console.error;
    console.error = (...args) => errors.push(args.join(" "));
    try {
        const loader = new CustomRootLoader({
            isGroupLoaded: (id) => loaded.has(id),
            fetchInterfaceDefinition: () => Promise.reject(new Error("network down")),
            setCustomInterface: () => true,
            handleWidgetPayload: () => {},
            pendingInterfaceUpdates,
        });
        loader.load(30020, () => applyCalls.push(30020));
        await new Promise((res) => setTimeout(res, 0));
        assert.deepEqual(applyCalls, [], "apply is not called on rejection");
        assert.equal(pendingInterfaceUpdates.size, 0, "queue cancelled on rejection");
        assert.equal(errors.length, 1);
        assert.match(errors[0], /fetch for root 30020 failed/);
    } finally {
        console.error = originalError;
    }
});

test("stale fetch's rejection cannot cancel the newer request's queue", async () => {
    const loaded = new Set<number>();
    const pendingInterfaceUpdates = new PendingInterfaceUpdates();
    const applyCalls: number[] = [];
    let rejectFirst!: (err: unknown) => void;
    let fetchCount = 0;
    const originalError = console.error;
    console.error = () => { /* silence */ };
    try {
        const loader = new CustomRootLoader({
            isGroupLoaded: (id) => loaded.has(id),
            fetchInterfaceDefinition: (groupId) => {
                fetchCount++;
                if (fetchCount === 1) {
                    return new Promise((_res, rej) => { rejectFirst = rej; });
                }
                // Second fetch: never resolves in this test; we just verify the token.
                return new Promise(() => { /* pending */ });
            },
            setCustomInterface: () => true,
            handleWidgetPayload: () => {},
            pendingInterfaceUpdates,
        });
        loader.load(30020, () => applyCalls.push(30020));
        loader.load(30021, () => applyCalls.push(30021));
        // Reject the first fetch AFTER the second is in flight.
        rejectFirst(new Error("stale network"));
        await new Promise((res) => setTimeout(res, 0));
        // The second request's queue must still be open.
        assert.equal(pendingInterfaceUpdates.size, 1, "newer request's queue survives stale rejection");
    } finally {
        console.error = originalError;
    }
});

test("ordering contract: uid-bearing updates during fetch are buffered; others pass through", () => {
    // The loader's docstring states: server-defined roots buffer payloads carrying a
    // widget uid (set_text, set_flags, set_item, set_hidden, open_sub by targetUid)
    // and do NOT buffer payloads without one (run_script, close_sub without a uid).
    // This test pins the contract so a future change to PendingInterfaceUpdates
    // doesn't silently widen or narrow it.
    const h = makeHarness();
    h.loader.load(30020, h.apply({ groupId: 30020 }));
    // uid-bearing payload for the pending group — buffered.
    assert.equal(
        h.pendingInterfaceUpdates.defer({ action: "set_text", uid: (30020 << 16) | 3 }),
        true,
    );
    assert.equal(
        h.pendingInterfaceUpdates.defer({ action: "set_item", uid: (30020 << 16) | 5, itemId: 4151 }),
        true,
    );
    assert.equal(
        h.pendingInterfaceUpdates.defer({ action: "open_sub", targetUid: (30020 << 16) | 9, groupId: 100 }),
        true,
        "open_sub with targetUid in the pending group is buffered",
    );
    // No uid at all — passes through (caller must not rely on this for custom roots).
    assert.equal(
        h.pendingInterfaceUpdates.defer({ action: "run_script", scriptId: 123, args: [] }),
        false,
        "run_script is NOT buffered — server must not emit it in the same batch as set_root for a custom root",
    );
    assert.equal(
        h.pendingInterfaceUpdates.defer({ action: "close_sub", targetUid: (161 << 16) | 16 }),
        false,
        "close_sub for a different group is not buffered by this pending root",
    );
});

test("CUSTOM_INTERFACE_START boundary — one below is cache, equal-or-above is custom", () => {
    assert.equal(CUSTOM_INTERFACE_START, 30_000, "sanity: boundary constant");
    const h = makeHarness();
    const belowRan = h.loader.load(CUSTOM_INTERFACE_START - 1, h.apply({ groupId: CUSTOM_INTERFACE_START - 1 }));
    assert.equal(belowRan, true, "29999 is below the custom range and runs synchronously");
    assert.deepEqual(h.fetchCalls, []);
    const atRan = h.loader.load(CUSTOM_INTERFACE_START, h.apply({ groupId: CUSTOM_INTERFACE_START }));
    assert.equal(atRan, false, "30000 is in the custom range and triggers a fetch");
    assert.deepEqual(h.fetchCalls, [CUSTOM_INTERFACE_START]);
});
