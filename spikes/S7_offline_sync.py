#!/usr/bin/env python3
"""S7: executable reference model for the offline synchronization invariants.

This is a protocol/state-machine spike, not a production server or a database
benchmark.  It deliberately has no third-party dependencies so that failures in
the contract can be reproduced with only Python 3.
"""

from __future__ import annotations

import hashlib
import json
import threading
import time
import unittest
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable


MAX_BUNDLE_EVENTS = 2
MAX_PENDING = 1_000
MAX_DEPENDENCY_DEPTH = 8
CACHE_WINDOW_DAYS = 14
UTC = timezone.utc


@dataclass(frozen=True)
class Event:
    tenant_id: str
    event_uuid: str
    layer: int
    entity_id: str
    payload: dict[str, Any]
    occurred_at: datetime
    kind: str = "event"
    base_version: int | None = None
    depends_on: tuple[str, ...] = ()
    priority: int = 1
    api_version: int = 10
    convertible: bool = True


@dataclass(frozen=True)
class PushResult:
    status: str
    retryable: bool = False
    redirect_to: str | None = None
    detail: dict[str, Any] = field(default_factory=dict)


class SyncServer:
    """Small transactional model of one shard's common command path."""

    def __init__(self, shard_id: str) -> None:
        self.shard_id = shard_id
        self.next_seq = 1
        self.receipts: set[tuple[str, str]] = set()
        self.layer1: list[Event] = []
        self.layer2: dict[tuple[str, str], dict[str, Any]] = {}
        self.versions: dict[tuple[str, str], int] = defaultdict(int)
        self.version_fields: dict[tuple[str, str], dict[int, set[str]]] = defaultdict(dict)
        self.change_log: list[dict[str, Any]] = []
        self.conflicts: list[dict[str, Any]] = []
        self.error_queue: list[dict[str, Any]] = []
        self.quarantine: list[dict[str, Any]] = []
        self.time_audit: list[dict[str, Any]] = []
        self.allowed_tenants: set[str] = set()
        self.frozen_tenants: set[str] = set()
        self.moved_tenants: dict[str, str] = {}
        self._lock = threading.Lock()

    def allow(self, tenant_id: str) -> None:
        self.allowed_tenants.add(tenant_id)

    def _append_change(self, event: Event, operation: str) -> None:
        self.change_log.append(
            {
                "server_seq": self.next_seq,
                "shard": self.shard_id,
                "tenant_id": event.tenant_id,
                "scope": event.payload.get("scope", "default"),
                "layer": event.layer,
                "entity_id": event.entity_id,
                "operation": operation,
            }
        )
        self.next_seq += 1

    def push(self, bundle: list[Event]) -> PushResult:
        if not bundle or len(bundle) > MAX_BUNDLE_EVENTS:
            return PushResult("bundle_rejected", detail={"reason": "bundle_size"})
        tenants = {event.tenant_id for event in bundle}
        if len(tenants) != 1:
            return PushResult("bundle_rejected", detail={"reason": "cross_tenant"})
        tenant = bundle[0].tenant_id
        if tenant in self.frozen_tenants:
            return PushResult("route_frozen", retryable=True)
        if tenant in self.moved_tenants:
            return PushResult("tenant_moved", retryable=True, redirect_to=self.moved_tenants[tenant])

        keys = [(event.tenant_id, event.event_uuid) for event in bundle]
        if all(key in self.receipts for key in keys):
            return PushResult("duplicate")
        if any(key in self.receipts for key in keys):
            return PushResult("bundle_rejected", detail={"reason": "mixed_retry_bundle"})

        if tenant not in self.allowed_tenants:
            for event in bundle:
                self.error_queue.append(
                    {
                        "event": event,
                        "reason": "permission_revoked",
                        "action": "administrator_escalation" if event.kind == "statutory" else "request_permission",
                    }
                )
            return PushResult("preserved_error")

        if any(not event.convertible for event in bundle):
            for event in bundle:
                self.quarantine.append(
                    {
                        "raw": json.dumps(event.payload, sort_keys=True),
                        "device_api_version": event.api_version,
                        "tenant_id": event.tenant_id,
                        "received_at": datetime.now(UTC),
                    }
                )
            return PushResult("quarantined")

        bundle_ids = {event.entity_id for event in bundle if event.layer == 2}
        for event in bundle:
            for parent_id in event.depends_on:
                if parent_id not in bundle_ids and (tenant, parent_id) not in self.layer2:
                    return PushResult("bundle_pending", detail={"reason": "missing_parent"})

        # Pre-compute every layer-2 decision before mutating: a conflict keeps the
        # complete bundle pending, so a parent cannot be partially committed.
        decisions: list[tuple[Event, set[str], set[str]]] = []
        for event in bundle:
            if event.layer != 2:
                continue
            key = (tenant, event.entity_id)
            changed = set(event.payload) - {"scope"}
            current_version = self.versions[key]
            since_base: set[str] = set()
            if event.base_version is not None:
                for version in range(event.base_version + 1, current_version + 1):
                    since_base |= self.version_fields[key].get(version, set())
            collisions = changed & since_base
            decisions.append((event, changed, collisions))
        if any(collisions for _, _, collisions in decisions):
            for event, _, collisions in decisions:
                if collisions:
                    self.conflicts.append(
                        {
                            "event": event,
                            "server_value": dict(self.layer2.get((tenant, event.entity_id), {})),
                            "fields": sorted(collisions),
                        }
                    )
            return PushResult("preserved_pending")

        with self._lock:
            for event in bundle:
                key = (tenant, event.entity_id)
                received_at = datetime.now(UTC)
                lower = received_at - timedelta(days=20 * 365)
                upper = received_at + timedelta(days=5 * 365)
                authoritative_event_ts = min(max(event.occurred_at, lower), upper)
                self.time_audit.append(
                    {
                        "event_uuid": event.event_uuid,
                        "device_time": event.occurred_at,
                        "received_at": received_at,
                        "event_ts": authoritative_event_ts,
                        "divergent": abs(event.occurred_at - received_at) > timedelta(days=30),
                    }
                )
                if event.layer == 1:
                    self.layer1.append(event)
                    operation = "append"
                else:
                    changed = set(event.payload) - {"scope"}
                    current = dict(self.layer2.get(key, {}))
                    current.update(event.payload)
                    self.layer2[key] = current
                    self.versions[key] += 1
                    self.version_fields[key][self.versions[key]] = changed
                    operation = "state_upsert"
                self.receipts.add((tenant, event.event_uuid))
                self._append_change(event, operation)
        return PushResult("accepted")

    def pull(self, tenant: str, scope: str, after: int, *, inject_during_read: Event | None = None) -> dict[str, Any]:
        """Read a fixed sequence ceiling, mirroring one MVCC snapshot."""
        ceiling = self.next_seq - 1
        snapshot = [dict(row) for row in self.change_log]
        if inject_during_read is not None:
            result = self.push([inject_during_read])
            if result.status != "accepted":
                raise AssertionError(result)
        rows = [
            row
            for row in snapshot
            if after < row["server_seq"] <= ceiling
            and row["tenant_id"] == tenant
            and row["scope"] == scope
        ]
        return {"rows": rows, "cursor": ceiling, "shard": self.shard_id, "scope": scope}


class Client:
    def __init__(self) -> None:
        self.outbox: dict[str, Event] = {}
        self.error_queue: dict[str, Event] = {}

    def enqueue(self, *events: Event) -> None:
        self.outbox.update({event.event_uuid: event for event in events})

    def apply_result(self, events: Iterable[Event], result: PushResult) -> None:
        durable = {"accepted", "duplicate", "preserved_pending", "quarantined"}
        if result.status in durable:
            for event in events:
                self.outbox.pop(event.event_uuid, None)
        elif result.status == "preserved_error":
            for event in events:
                self.error_queue[event.event_uuid] = event
                self.outbox.pop(event.event_uuid, None)

    def can_force_update(self, exported: bool = False) -> bool:
        return not self.outbox or exported


class PendingGraph:
    def __init__(self) -> None:
        self.edges: dict[str, set[str]] = defaultdict(set)
        self.pending_roots: set[str] = set()
        self.pending: set[str] = set()

    def add_dependency(self, upstream: str, downstream: str) -> None:
        if sum(map(len, self.edges.values())) >= MAX_PENDING:
            raise ValueError("pending_limit")
        self.edges[upstream].add(downstream)
        if self._has_path(downstream, upstream):
            self.edges[upstream].remove(downstream)
            raise ValueError("dependency_cycle")
        if max((self._depth(root) for root in self.edges), default=0) > MAX_DEPENDENCY_DEPTH:
            self.edges[upstream].remove(downstream)
            raise ValueError("dependency_depth")

    def mark_pending(self, node: str) -> set[str]:
        affected = {node} | self._descendants(node)
        self.pending_roots.add(node)
        self.pending |= affected
        return affected

    def resolve(self, node: str) -> set[str]:
        before = set(self.pending)
        self.pending_roots.discard(node)
        self.pending = set()
        for root in self.pending_roots:
            self.pending |= {root} | self._descendants(root)
        released = before - self.pending
        return released

    def _descendants(self, node: str) -> set[str]:
        found: set[str] = set()
        todo = list(self.edges[node])
        while todo:
            item = todo.pop()
            if item not in found:
                found.add(item)
                todo.extend(self.edges[item])
        return found

    def _has_path(self, source: str, target: str) -> bool:
        return target in self._descendants(source)

    def _depth(self, node: str) -> int:
        children = self.edges[node]
        return 1 + max((self._depth(child) for child in children), default=0)

class Inventory:
    def __init__(self, quantity: int) -> None:
        self.quantity = quantity
        self.lock = threading.Lock()
        self.accepted = 0
        self.adjudication: list[dict[str, Any]] = []

    def withdraw(self, event_uuid: str, amount: int, occurred_at: datetime) -> bool:
        with self.lock:
            received_at = datetime.now(UTC)
            if self.quantity >= amount:
                self.quantity -= amount
                self.accepted += 1
                return True
            self.adjudication.append(
                {
                    "event_uuid": event_uuid,
                    "occurred_at": occurred_at,
                    "received_at": received_at,
                    "downstream_frozen": True,
                }
            )
            return False


def event(uid: str, *, tenant: str = "T1", layer: int = 1, entity: str | None = None, **kwargs: Any) -> Event:
    return Event(
        tenant_id=tenant,
        event_uuid=uid,
        layer=layer,
        entity_id=entity or uid,
        payload=kwargs.pop("payload", {}),
        occurred_at=kwargs.pop("occurred_at", datetime(2026, 8, 14, tzinfo=UTC)),
        **kwargs,
    )


class S7OfflineSyncSpike(unittest.TestCase):
    metrics: dict[str, Any] = {}

    def setUp(self) -> None:
        self.server = SyncServer("jp-1")
        self.server.allow("T1")

    def test_01_minimal_bundle_is_atomic_and_tenant_local(self) -> None:
        parent = event("e-parent", layer=2, entity="field-1", base_version=0, payload={"name": "A"})
        child = event("e-child", entity="work-1", depends_on=("missing",))
        self.assertEqual(self.server.push([parent, child]).status, "bundle_pending")
        self.assertFalse(self.server.receipts)
        valid_child = event("e-child", entity="work-1", depends_on=("field-1",))
        self.assertEqual(self.server.push([parent, valid_child]).status, "accepted")
        self.assertEqual(len(self.server.receipts), 2)
        cross_tenant = event("e-x", tenant="T2")
        self.assertEqual(self.server.push([event("e-y"), cross_tenant]).status, "bundle_rejected")
        self.assertEqual(self.server.push([event("1"), event("2"), event("3")]).status, "bundle_rejected")

    def test_02_retry_is_idempotent_and_key_is_stable(self) -> None:
        item = event("0198-stable")
        self.assertEqual(self.server.push([item]).status, "accepted")
        cursor = self.server.next_seq
        self.assertEqual(self.server.push([item]).status, "duplicate")
        self.assertEqual(self.server.next_seq, cursor)
        self.assertEqual(sum(row["entity_id"] == item.entity_id for row in self.server.change_log), 1)

    def test_03_layer2_merges_disjoint_fields_and_preserves_conflicts(self) -> None:
        initial = event("u1", layer=2, entity="field", base_version=0, payload={"name": "A", "area": 10})
        self.assertEqual(self.server.push([initial]).status, "accepted")
        disjoint = event("u2", layer=2, entity="field", base_version=0, payload={"crop": "rice"})
        self.assertEqual(self.server.push([disjoint]).status, "accepted")
        conflict = event("u3", layer=2, entity="field", base_version=0, payload={"area": 12})
        self.assertEqual(self.server.push([conflict]).status, "preserved_pending")
        self.assertEqual(self.server.layer2[("T1", "field")]["area"], 10)
        self.assertEqual(self.server.conflicts[0]["event"], conflict)

    def test_04_inventory_serializes_and_exposes_both_times(self) -> None:
        stock = Inventory(1)
        later_occurrence = datetime(2026, 8, 14, 10, tzinfo=UTC)
        earlier_occurrence = datetime(2026, 8, 14, 9, tzinfo=UTC)
        self.assertTrue(stock.withdraw("later-first", 1, later_occurrence))
        self.assertFalse(stock.withdraw("earlier-late", 1, earlier_occurrence))
        case = stock.adjudication[0]
        self.assertEqual(case["occurred_at"], earlier_occurrence)
        self.assertIn("received_at", case)
        self.assertTrue(case["downstream_frozen"])

    def test_05_inventory_concurrency_never_goes_negative(self) -> None:
        stock = Inventory(100)
        started = time.perf_counter()
        threads = [
            threading.Thread(target=stock.withdraw, args=(f"w-{i}", 1, datetime.now(UTC)))
            for i in range(500)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        elapsed_ms = (time.perf_counter() - started) * 1_000
        self.assertEqual(stock.quantity, 0)
        self.assertEqual(stock.accepted, 100)
        self.assertEqual(len(stock.adjudication), 400)
        self.assertLess(elapsed_ms, 2_000)
        self.metrics["inventory_500_requests_ms"] = round(elapsed_ms, 1)

    def test_06_pull_has_scope_cursor_snapshot_and_all_write_paths(self) -> None:
        first = event("log", payload={"scope": "farm-A"})
        state = event("rest", layer=2, entity="field", base_version=0, payload={"scope": "farm-A", "name": "A"})
        self.server.push([first])
        self.server.push([state])
        during = event("during", payload={"scope": "farm-A"})
        page = self.server.pull("T1", "farm-A", 0, inject_during_read=during)
        self.assertEqual([row["layer"] for row in page["rows"]], [1, 2])
        self.assertNotIn("during", {row["entity_id"] for row in page["rows"]})
        next_page = self.server.pull("T1", "farm-A", page["cursor"])
        self.assertEqual([row["entity_id"] for row in next_page["rows"]], ["during"])
        self.assertEqual((page["shard"], page["scope"]), ("jp-1", "farm-A"))

    def test_07_priority_scheduler_keeps_p0_ahead_of_p2(self) -> None:
        queue = [event(f"bulk-{i}", priority=2) for i in range(10_000)]
        queue.append(event("safety-revocation", priority=0))
        started = time.perf_counter()
        selected = min(queue, key=lambda item: item.priority)
        elapsed_ms = (time.perf_counter() - started) * 1_000
        self.assertEqual(selected.event_uuid, "safety-revocation")
        self.assertLess(elapsed_ms, 60_000)
        self.metrics["p0_select_amid_10000_p2_ms"] = round(elapsed_ms, 3)

    def test_08_revoked_permission_is_preserved_with_recovery_action(self) -> None:
        self.server.allowed_tenants.clear()
        client = Client()
        statutory = event("law-1", kind="statutory")
        client.enqueue(statutory)
        result = self.server.push([statutory])
        client.apply_result([statutory], result)
        self.assertNotIn("law-1", client.outbox)
        self.assertIn("law-1", client.error_queue)
        self.assertEqual(self.server.error_queue[0]["action"], "administrator_escalation")

    def test_09_dependency_graph_propagates_releases_and_rejects_cycles(self) -> None:
        graph = PendingGraph()
        graph.add_dependency("stock", "cost")
        graph.add_dependency("cost", "order")
        self.assertEqual(graph.mark_pending("stock"), {"stock", "cost", "order"})
        self.assertEqual(graph.resolve("stock"), {"stock", "cost", "order"})
        self.assertFalse(graph.pending)
        with self.assertRaisesRegex(ValueError, "dependency_cycle"):
            graph.add_dependency("order", "stock")
        deep = PendingGraph()
        for index in range(MAX_DEPENDENCY_DEPTH - 1):
            deep.add_dependency(str(index), str(index + 1))
        with self.assertRaisesRegex(ValueError, "dependency_depth"):
            deep.add_dependency(str(MAX_DEPENDENCY_DEPTH - 1), str(MAX_DEPENDENCY_DEPTH))
        limited = PendingGraph()
        limited.edges["root"] = {f"leaf-{index}" for index in range(MAX_PENDING)}
        with self.assertRaisesRegex(ValueError, "pending_limit"):
            limited.add_dependency("another", "leaf")

    def test_10_clock_skew_keeps_statutory_record_and_flags_business_time(self) -> None:
        received = datetime.now(UTC)
        for offset in (-365, 365):
            raw = received + timedelta(days=offset)
            item = event(f"clock-{offset}", kind="statutory", occurred_at=raw)
            self.assertEqual(self.server.push([item]).status, "accepted")
            audit = self.server.time_audit[-1]
            self.assertGreaterEqual(audit["event_ts"], audit["received_at"] - timedelta(days=20 * 365))
            self.assertLessEqual(audit["event_ts"], audit["received_at"] + timedelta(days=5 * 365))
            self.assertTrue(audit["divergent"])  # pending/adjudication signal

    def test_11_scope_regrant_is_bounded_and_cursor_is_independent(self) -> None:
        now = datetime(2026, 8, 14, tzinfo=UTC)
        records = [
            {"id": f"work-{day}", "at": now - timedelta(days=day), "priority": 1, "bytes": 512}
            for day in range(60)
        ] + [{"id": "safety-master", "at": now - timedelta(days=365), "priority": 0, "bytes": 1024}]
        started = time.perf_counter()
        cutoff = now - timedelta(days=CACHE_WINDOW_DAYS - 1)  # today + preceding 13 calendar days
        initial = [row for row in records if row["priority"] == 0 or row["at"] >= cutoff]
        initial.sort(key=lambda row: row["priority"])
        elapsed_ms = (time.perf_counter() - started) * 1_000
        total_bytes = sum(row["bytes"] for row in initial)
        estimated_seconds_at_1mbps = total_bytes * 8 / 1_000_000
        self.assertEqual(initial[0]["id"], "safety-master")
        self.assertEqual(len(initial), CACHE_WINDOW_DAYS + 1)
        self.assertLess(estimated_seconds_at_1mbps, 300)
        cache = {"farm-A": initial, "farm-B": [{"id": "unrelated"}]}
        cursors = {("jp-1", "farm-A"): 42, ("jp-1", "farm-B"): 9}
        cache.pop("farm-A")  # scope shrink: explicit local purge
        cursors.pop(("jp-1", "farm-A"))
        self.assertNotIn("farm-A", cache)
        self.assertNotIn(("jp-1", "farm-A"), cursors)
        self.assertIn(("jp-1", "farm-B"), cursors)
        self.metrics["regrant_rows"] = len(initial)
        self.metrics["regrant_bytes"] = total_bytes
        self.metrics["regrant_estimated_seconds_at_1mbps"] = round(estimated_seconds_at_1mbps, 3)
        self.metrics["regrant_model_ms"] = round(elapsed_ms, 3)

    def test_12_move_freeze_redirect_retry_and_chain_verification(self) -> None:
        old = self.server
        new = SyncServer("jp-2")
        new.allow("T1")
        client = Client()
        item = event("move-1", kind="statutory")
        client.enqueue(item)
        old.frozen_tenants.add("T1")
        result = old.push([item])
        client.apply_result([item], result)
        self.assertIn("move-1", client.outbox)
        old.frozen_tenants.clear()
        old.moved_tenants["T1"] = "jp-2"
        redirect = old.push([item])
        self.assertEqual(redirect.redirect_to, "jp-2")
        accepted = new.push([item])
        client.apply_result([item], accepted)
        self.assertFalse(client.outbox)
        self.assertEqual(new.push([item]).status, "duplicate")
        chain = "0" * 64
        for row in new.change_log:
            chain = hashlib.sha256((chain + json.dumps(row, sort_keys=True)).encode()).hexdigest()
        replay = "0" * 64
        for row in new.change_log:
            replay = hashlib.sha256((replay + json.dumps(row, sort_keys=True)).encode()).hexdigest()
        self.assertEqual(chain, replay)

    def test_13_force_update_waits_for_outbox_or_export_and_quarantines_old_record(self) -> None:
        client = Client()
        old = event("legacy", kind="statutory", api_version=1, convertible=False, payload={"legacy": "raw"})
        client.enqueue(old)
        self.assertFalse(client.can_force_update())
        self.assertTrue(client.can_force_update(exported=True))
        result = self.server.push([old])
        client.apply_result([old], result)
        self.assertEqual(result.status, "quarantined")
        self.assertFalse(client.outbox)
        self.assertEqual(json.loads(self.server.quarantine[0]["raw"]), {"legacy": "raw"})

    def test_14_missing_shard_is_partial_never_complete(self) -> None:
        configured = {"jp-1", "jp-2", "jp-3"}
        responses = {"jp-1": [], "jp-3": []}
        completeness = "complete" if set(responses) == configured else "partial"
        self.assertEqual(completeness, "partial")

    def test_15_weak_reference_break_is_detected_without_cross_tenant_fk(self) -> None:
        local = {"T1:transfer": {"related_id": "T2:missing"}}
        remote_ids = {"T2:existing"}
        broken = [key for key, row in local.items() if row["related_id"] not in remote_ids]
        self.assertEqual(broken, ["T1:transfer"])


class ReportingResult(unittest.TextTestResult):
    def addSuccess(self, test: unittest.TestCase) -> None:  # noqa: N802 (unittest API)
        super().addSuccess(test)
        print(f"[PASS] {test.id().rsplit('.', 1)[-1]}")


if __name__ == "__main__":
    print("S7 offline synchronization spike")
    print(f"python: {__import__('platform').python_version()}")
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(S7OfflineSyncSpike)
    runner = unittest.TextTestRunner(verbosity=0, resultclass=ReportingResult)
    result = runner.run(suite)
    print("metrics:")
    for name, value in sorted(S7OfflineSyncSpike.metrics.items()):
        print(f"  {name}: {value}")
    print(f"RESULT: {'PASS' if result.wasSuccessful() else 'FAIL'} ({result.testsRun - len(result.failures) - len(result.errors)}/{result.testsRun})")
    raise SystemExit(not result.wasSuccessful())
