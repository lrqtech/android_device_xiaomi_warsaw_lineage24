#!/usr/bin/env python3
"""Check or apply the Warsaw platform series to its exact pinned base."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def git(project, *args):
    return subprocess.run(['git', '-C', str(project), *args], text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Android source root')
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--check', action='store_true', help='Validate without modifying files (default)')
    action.add_argument('--apply', action='store_true', help='Apply missing patches after all checks pass')
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    series_dir = Path(__file__).resolve().parent / 'patches/platform'
    series = json.loads((series_dir / 'series.json').read_text())
    pending = []
    result = []
    errors = []
    seen = set()
    for item in series['projects']:
        name = item['project']
        try:
            if name in seen:
                raise ValueError('duplicate project')
            seen.add(name)
            project = (source / name).resolve(strict=True)
            project.relative_to(source)
            patch = (series_dir / item['patch']).resolve(strict=True)
            patch.relative_to(series_dir.resolve())
            if hashlib.sha256(patch.read_bytes()).hexdigest() != item['sha256']:
                raise ValueError('patch digest mismatch')
            head = git(project, 'rev-parse', 'HEAD')
            if head.returncode or head.stdout.strip() != item['base_revision']:
                raise ValueError('HEAD does not match the pinned base revision')
            if git(project, 'apply', '--check', '--reverse', str(patch)).returncode == 0:
                result.append({'project': name, 'state': 'already_applied'})
                continue
            status = git(project, 'status', '--porcelain')
            if status.returncode or status.stdout.strip():
                raise ValueError('worktree is not clean and the complete patch is not already applied')
            checked = git(project, 'apply', '--check', str(patch))
            if checked.returncode:
                raise ValueError('patch check failed: ' + checked.stderr.strip())
            pending.append((project, patch))
            result.append({'project': name, 'state': 'ready'})
        except (ValueError, OSError) as exc:
            errors.append({'project': name, 'error': str(exc)})
    if errors:
        print(json.dumps({'ok': False, 'errors': errors, 'projects': result}, indent=2))
        return 1
    if args.apply:
        for project, patch in pending:
            applied = git(project, 'apply', str(patch))
            if applied.returncode:
                print(applied.stderr, file=sys.stderr)
                print('Application stopped; preserve the worktrees and inspect this failure.', file=sys.stderr)
                return 1
        for item in result:
            if item['state'] == 'ready':
                item['state'] = 'applied'
    print(json.dumps({'ok': True, 'action': 'apply' if args.apply else 'check', 'projects': result}, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
