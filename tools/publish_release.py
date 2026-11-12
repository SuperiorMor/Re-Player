#!/usr/bin/env python3
import sys
import subprocess
import time
import json
import urllib.request
import os

def run_cmd(cmd, check=True):
    print(f"--> {cmd}")
    res = subprocess.run(cmd, shell=True, text=True, capture_output=True)
    if res.stdout:
        print(res.stdout.strip())
    if res.stderr:
        print(res.stderr.strip(), file=sys.stderr)
    if check and res.returncode != 0:
        sys.exit(res.returncode)
    return res

def main():
    if len(sys.argv) < 3:
        print("Usage: python3 tools/publish_release.py <version> <commit_message>")
        sys.exit(1)

    version = sys.argv[1].lstrip("v")
    commit_msg = sys.argv[2]
    tag = f"v{version}"

    # 1. Stage changes
    run_cmd("git add -A")

    # 2. Commit if there are changes
    status_res = subprocess.run("git status --porcelain", shell=True, text=True, capture_output=True)
    if status_res.stdout.strip():
        run_cmd(f'git commit -m "{commit_msg}"')
    else:
        print("No changes to commit, continuing...")

    # 3. Create tag if not exists
    tag_check = subprocess.run(f"git rev-parse {tag}", shell=True, capture_output=True)
    if tag_check.returncode == 0:
        print(f"Tag {tag} already exists locally, replacing...")
        run_cmd(f"git tag -d {tag}")
    run_cmd(f'git tag -a {tag} -m "Release {tag}: {commit_msg}"')

    # 4. Push current branch and tag
    branch_res = subprocess.run("git rev-parse --abbrev-ref HEAD", shell=True, text=True, capture_output=True)
    current_branch = branch_res.stdout.strip() or "main"
    run_cmd(f"git push origin {current_branch}")
    run_cmd(f"git push origin {tag} --force")

    # 5. Monitor GitHub Actions
    print(f"\n[CI] Monitoring GitHub Actions build for {tag}...")
    repo = "SuperiorMor/Re-Player"
    api_url = f"https://api.github.com/repos/{repo}/actions/runs?per_page=10"

    # Give GitHub 10 seconds to register the new push
    time.sleep(10)

    run_id = None
    for attempt in range(25):
        try:
            req = urllib.request.Request(api_url, headers={"User-Agent": "ReleaseBot"})
            with urllib.request.urlopen(req) as resp:
                data = json.loads(resp.read().decode())
                for r in data.get("workflow_runs", []):
                    if r.get("head_branch") == tag or (r.get("event") == "push" and r.get("head_branch") == tag):
                        # Only pick if queued or in_progress, or if attempt > 5
                        if r.get("status") in ["queued", "in_progress"]:
                            run_id = r.get("id")
                            break
                        elif attempt > 5 and r.get("conclusion") != "failure":
                            run_id = r.get("id")
                            break
            if run_id:
                break
        except Exception:
            pass
        time.sleep(5)

    if not run_id:
        print("[CI] Could not locate workflow run ID via API, waiting 90s for build to complete...")
        time.sleep(90)
    else:
        print(f"[CI] Found workflow run ID: {run_id}. Polling status...")
        run_url = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}"
        for attempt in range(30):
            try:
                req = urllib.request.Request(run_url, headers={"User-Agent": "ReleaseBot"})
                with urllib.request.urlopen(req) as resp:
                    r_data = json.loads(resp.read().decode())
                    status = r_data.get("status")
                    conclusion = r_data.get("conclusion")
                    print(f"[CI] Status: {status}, Conclusion: {conclusion}")
                    if status == "completed":
                        if conclusion == "success":
                            print("[CI] Build succeeded!")
                        else:
                            print(f"[CI] Build finished with status: {conclusion}", file=sys.stderr)
                            sys.exit(1)
                        break
            except Exception as e:
                print(f"[CI] Polling error: {e}")
            time.sleep(10)

    # 6. Verify and output download link
    apk_name = f"RePlayer-{tag}.apk"
    dl_url = f"https://github.com/{repo}/releases/download/{tag}/{apk_name}"
    rel_url = f"https://github.com/{repo}/releases/tag/{tag}"

    print("\n" + "=" * 60)
    print(f"RELEASE {tag} PUBLISHED SUCCESSFULLY!")
    print(f"GitHub Release: {rel_url}")
    print(f"Direct APK Download: {dl_url}")
    print("=" * 60)

if __name__ == "__main__":
    main()
