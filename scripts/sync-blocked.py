#!/usr/bin/env python3
"""Keep `blocked` labels in sync with the `Blocked by:` lines of open issues.

Usage: scripts/sync-blocked.py [--check]

For every open issue, reads `Blocked by: #N, #M` from its body. An issue with
at least one open blocker gets the `blocked` label; an issue whose blockers
are all closed loses it. Also reports non-reciprocal `Blocks:` links and open
agent issues that do not (transitively) wait on an open human issue.

With --check nothing is changed; the exit code is 1 if anything is off.
Requires the GitHub CLI (`gh`), authenticated for this repository.
"""

import json
import re
import subprocess
import sys


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], capture_output=True, text=True, check=True).stdout


def refs(body: str, field: str) -> set[int]:
    match = re.search(rf"^{field}:(.*)$", body or "", re.MULTILINE)
    return {int(n) for n in re.findall(r"#(\d+)", match.group(1))} if match else set()


def main() -> int:
    check_only = "--check" in sys.argv[1:]
    issues = json.loads(
        gh("issue", "list", "--state", "all", "--limit", "500",
           "--json", "number,state,labels,body,title")
    )
    by_number = {i["number"]: i for i in issues}
    for issue in issues:
        issue["label_names"] = {label["name"] for label in issue["labels"]}
        issue["blocked_by"] = refs(issue["body"], "Blocked by")
        issue["blocks"] = refs(issue["body"], "Blocks")

    def is_open(number: int) -> bool:
        return number in by_number and by_number[number]["state"] == "OPEN"

    problems = 0
    for issue in sorted(issues, key=lambda i: i["number"]):
        if issue["state"] != "OPEN":
            continue
        number = issue["number"]
        open_blockers = sorted(n for n in issue["blocked_by"] if is_open(n))
        has_label = "blocked" in issue["label_names"]
        if open_blockers and not has_label:
            problems += 1
            print(f"#{number}: add `blocked` (waiting on {open_blockers})")
            if not check_only:
                gh("issue", "edit", str(number), "--add-label", "blocked")
                issue["label_names"].add("blocked")
        elif not open_blockers and has_label:
            problems += 1
            print(f"#{number}: remove `blocked` (all blockers closed)")
            if not check_only:
                gh("issue", "edit", str(number), "--remove-label", "blocked")
                issue["label_names"].discard("blocked")
        for blocker in issue["blocked_by"]:
            if blocker in by_number and number not in by_number[blocker]["blocks"]:
                print(f"#{blocker}: `Blocks:` does not list #{number} (fix by hand)")
                problems += 1

    def waits_on_human(number: int, seen: frozenset[int] = frozenset()) -> bool:
        for blocker in by_number[number]["blocked_by"]:
            if not is_open(blocker) or blocker in seen:
                continue
            if "human" in by_number[blocker]["label_names"]:
                return True
            if waits_on_human(blocker, seen | {number}):
                return True
        return False

    workable = []
    for issue in sorted(issues, key=lambda i: i["number"]):
        if issue["state"] != "OPEN" or "agent" not in issue["label_names"]:
            continue
        if not any(is_open(n) for n in issue["blocked_by"]):
            workable.append(issue)
        elif not waits_on_human(issue["number"]):
            print(f"#{issue['number']}: blocked, but not by any open human issue")

    print(f"\nWorkable agent issues: {len(workable)}")
    for issue in workable:
        print(f"  #{issue['number']} {issue['title']}")
    return 1 if (check_only and problems) else 0


if __name__ == "__main__":
    sys.exit(main())
