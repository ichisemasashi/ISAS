import assert from "node:assert/strict";
import test from "node:test";
import { loadPolicyFiles, validateCiPolicy } from "../check-ci-policy.mjs";

test("repository CI policy keeps immutable supply-chain and approval controls", async () => {
  assert.deepEqual(validateCiPolicy(await loadPolicyFiles()), []);
});

test("policy rejects mutable actions and base images", () => {
  const files = {
    ci: "uses: actions/checkout@v4\n",
    release: "uses: x/y@main\n",
    codeowners: "",
    dockerfiles: { bad: "FROM node:22\n" },
  };
  assert.ok(validateCiPolicy(files).some((error) => error.includes("not pinned")));
  assert.ok(validateCiPolicy(files).some((error) => error.includes("base image")));
});
