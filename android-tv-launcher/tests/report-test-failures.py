"""Turn failed JUnit results into GitHub annotations and a job summary.

CI artifacts and raw logs are often not convenient to open. Annotations appear directly on the pull
request and in the API, so the names and messages of failing device tests are always visible.

Usage: python3 report-test-failures.py <results-directory>
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 10  # GitHub shows at most 10 error annotations per step


def escape(text):
    # Workflow command encoding for annotation messages.
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def main(root):
    passed, failed = 0, []
    for path in sorted(glob.glob(os.path.join(root, '**', '*.xml'), recursive=True)):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for case in tree.iter('testcase'):
            problem = case.find('failure')
            if problem is None:
                problem = case.find('error')
            if problem is None:
                if case.find('skipped') is None:
                    passed += 1
                continue
            name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
            detail = (problem.get('message') or problem.text or '').strip()
            failed.append((name, detail))

    unique = sorted({name for name, _ in failed})
    print(f'Device tests: {passed} passed, {len(failed)} failed ({len(unique)} distinct)')
    for name, detail in failed[:MAX_ANNOTATIONS]:
        first_lines = '\n'.join(detail.splitlines()[:6])[:1500]
        print(f'::error title=Device test failed: {name}::{escape(first_lines)}')

    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as out:
            out.write(f'### Device tests: {passed} passed, {len(failed)} failed\n\n')
            for name, detail in failed:
                out.write(f'- `{name}`: {detail.splitlines()[0][:300] if detail else "no message"}\n')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else 'android-tv-launcher/app/build'))
