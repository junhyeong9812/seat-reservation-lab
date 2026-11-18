#!/usr/bin/env python3
"""ADR에서 줄글(표, 코드 블록, 번호 이동 안내 제외)만 줄 번호와 함께 뽑는다. 문장 단위 검토용. 사용: prose.py <파일>"""
import sys,re
for p in sys.argv[1:]:
    code=False
    for i,l in enumerate(open(p,encoding='utf-8'),1):
        if l.lstrip().startswith('```'): code=not code; continue
        if code or not l.strip() or l.lstrip().startswith('|') or l.startswith('> 번호'): continue
        print(f'{i}: {l.rstrip()}')
