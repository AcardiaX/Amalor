"""Format Git commits as Chinese-first release notes with bilingual headings."""

import argparse
import os
import re
import subprocess
from collections import Counter
from pathlib import Path
from urllib.parse import quote


CATEGORIES = {
    "features": ("✨ 新增功能", "✨ New Features", "新增：", "Added: "),
    "improvements": ("⚡ 优化与改进", "⚡ Improvements", "优化：", "Improved: "),
    "changes": ("🔄 变更", "🔄 Changes", "变更：", "Changed: "),
    "fixes": ("🐛 修复", "🐛 Fixes", "修复：", "Fixed: "),
    "docs": ("🌐 文档与翻译", "🌐 Docs & Translations", "文档与翻译：", "Docs & translations: "),
}
PREFIX = re.compile(r"^([a-z][a-z0-9-]*)(?:\([^)]*\))?!?:\s*", re.IGNORECASE)


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True,
                          encoding="utf-8", errors="replace").stdout.strip()


def resolve(ref):
    return git("rev-parse", "--verify", "--end-of-options", ref + "^{commit}")


def category(subject):
    match = PREFIX.match(subject)
    prefix = match[1].lower() if match else ""
    if prefix in ("feat", "feature"):
        return "features"
    if prefix in ("perf", "refactor"):
        return "improvements"
    if prefix in ("fix", "bugfix"):
        return "fixes"
    if prefix in ("docs", "i18n", "l10n", "translation", "translations"):
        return "docs"
    return "changes"


def contributor(email):
    match = re.fullmatch(r"(?:\d+\+)?([A-Za-z0-9-]{1,39})@users\.noreply\.github\.com", email.rstrip("."))
    return match[1] if match else None


def collect_commits(base, head):
    head = resolve(head)
    if base:
        base = resolve(base)
        if subprocess.run(["git", "merge-base", "--is-ancestor", base, head], capture_output=True).returncode:
            raise ValueError("Previous release must be an ancestor of the release commit")
    fields = git("log", "--no-merges", "--reverse", "--format=%H%x00%s%x00%b%x00%ae%x00",
                 f"{base}..{head}" if base else head).split("\0")
    if not fields[-1].strip():
        fields.pop()
    if len(fields) % 4:
        raise ValueError("Malformed Git commit records")
    commits = []
    for at in range(0, len(fields), 4):
        sha, subject, body, email = (part.strip() for part in fields[at:at + 4])
        if not re.fullmatch(r"[0-9a-f]{40,64}", sha):
            raise ValueError("Malformed Git commit id")
        authors = {handle for address in [email] + re.findall(r"(?im)^Co-authored-by:.*?<([^>]+)>\s*$", body)
                   if (handle := contributor(address))}
        commits.append({"id": sha[:12], "sha": sha, "subject": subject,
                        "kind": category(subject), "contributors": sorted(authors)})
    if len({commit["id"] for commit in commits}) != len(commits):
        raise ValueError("Ambiguous abbreviated commit ids")
    return commits


def escape_markdown(text):
    return re.sub(r"([\\`*_\[\]<>])", r"\\\1", " ".join(text.split()))


def render(commits, repository, tag, base):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository name")
    owner = repository.split("/", 1)[0].lower()
    counts = Counter(commit["kind"] for commit in commits)
    lines = ["[简体中文](#简体中文) | [English](#english)", ""]
    for language, title in ((0, "简体中文"), (1, "English")):
        lines += ["## " + title, ""]
        if commits:
            lines += ["> 以下为按提交类型自动分类的更新记录。" if language == 0 else
                      "> These updates are automatically categorized from Git commits.", ""]
            lines += ["### 🌟 亮点" if language == 0 else "### 🌟 Highlights", ""]
            lines += [f"- 本次包含 {len(commits)} 条提交：新增 {counts['features']}、优化 {counts['improvements']}、"
                      f"变更 {counts['changes']}、修复 {counts['fixes']}、文档与翻译 {counts['docs']}。" if language == 0 else
                      f"- {len(commits)} commits: {counts['features']} features, {counts['improvements']} improvements, "
                      f"{counts['changes']} changes, {counts['fixes']} fixes, and {counts['docs']} documentation/translation updates.", ""]
        lines += ["### 📝 更新内容" if language == 0 else "### 📝 What's Changed", ""]
        if not commits:
            lines += ["本次发布没有新的非合并提交。" if language == 0 else "No new non-merge commits in this release.", ""]
        for kind, labels in CATEGORIES.items():
            items = [commit for commit in commits if commit["kind"] == kind]
            if not items:
                continue
            lines += ["#### " + labels[language], ""]
            for commit in items:
                handles = [handle for handle in commit["contributors"] if handle.lower() != owner]
                credit = " " + " ".join("@" + handle for handle in handles) if handles else ""
                subject = escape_markdown(PREFIX.sub("", commit["subject"]))
                lines.append("- " + labels[language + 2] + subject + credit)
            lines.append("")
        if language == 0:
            lines += ["---", ""]
    url = f"https://github.com/{repository}"
    lines.append(f"[完整变更 / Full changelog]({url}/compare/{quote(base, safe='')}...{quote(tag, safe='')})" if base else
                 f"[提交记录 / Commit history]({url}/commits/{quote(tag, safe='')})")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.environ.get("PREVIOUS_TAG", ""))
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--tag", default=os.environ.get("CURRENT_TAG", ""))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--output", type=Path, default=Path("release-notes.md"))
    args = parser.parse_args()
    if not args.tag or subprocess.run(["git", "check-ref-format", "refs/tags/" + args.tag], capture_output=True).returncode:
        parser.error("Provide a valid release tag with --tag or CURRENT_TAG")
    if not args.repository:
        remote = git("remote", "get-url", "origin")
        match = re.search(r"github\.com[/:]([^/]+/[^/]+?)(?:\.git)?$", remote)
        args.repository = match[1] if match else ""
    commits = collect_commits(args.base, args.head)
    output = render(commits, args.repository, args.tag, args.base)
    if not args.output.parent.is_dir():
        parser.error("Output directory does not exist")
    args.output.write_text(output, encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(output)
    print(f"Release notes: formatted commits; {len(commits)} commits; {args.output}")


if __name__ == "__main__":
    main()
