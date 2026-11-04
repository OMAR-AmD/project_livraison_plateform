# Definition of Done

> Process correction after the review on #1 (2026-11-04).

A ticket may move to **Done** only when **all** of the following are true:

1. The code is implemented and reviewed.
2. The commit is on `origin/main` — not only in a local branch or working tree.
3. CI is green on that commit.
4. The acceptance criteria are verified.
5. The evidence is linked in the issue: commit hash, CI run, test output.
6. The board status is updated only after points 1–5.

## Explicitly not Done

- Local-only implementation.
- A commit that exists only on a laptop.
- A green local test run with no pushed commit.
- A board update made before the evidence exists.
- A silent scope change after Done; follow-up work goes into a new ticket.

## Why

A Done item is a historical record. Moving it to Done before the code is on
`origin/main` and verified makes the board claim something the remote cannot
prove. The board must lag the evidence, never lead it.
