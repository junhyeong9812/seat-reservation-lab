#!/usr/bin/env python3
"""ADR-008(리뷰 루프 2 D2) 설정 파일 하나(표준 입력)에서 다른 설정을 끌어오는 키를 센다 — lib.sh limit_config_check가 부른다.

    git show <sha>:<path> | python3 cfgkeys.py <path>     → stdout: 걸린 키 수(정수 한 줄) · stderr: 걸린 키 이름(값은 내지 않는다)

대상 키(relaxed binding 기준 — 소문자·'-'·'_' 무시 비교): spring.config.import · spring.config.location · spring.config.additional-location ·
spring.profiles.active · spring.profiles.include · spring.profiles.group.<이름>(그 프로필을 켜면 다른 프로필이 함께 켜진다).
이 키들이 있으면 application.yml 밖의 설정(다른 파일·다른 프로필)이 매수 방식을 정할 수 있어 '기본값'을 잰 것인지 정적으로 말할 수 없다.
YAML은 문서 여러 개(---)·중첩·점 표기 키를 모두 펼쳐 본다(PyYAML safe_load_all). properties는 key=value · key: value(주석 #·! 제외).
파싱이 안 되면 exit 2 — 호출 쪽이 확인 실패로 드러낸다(조용히 0으로 세지 않게).

    git show <sha>:<path> | python3 cfgkeys.py --value <키>      → stdout: 그 키의 값 한 줄(YAML 스칼라 그대로 — true·false·문자열) · 키가 없으면 빈 줄
    (ADR-012 명세 §10 K9 — lib.sh app_default_is_old_c: 업무 앱 application.yml의 기본값이 옛 C인가. 키 비교는 위와 같은 relaxed 정규화 · YAML만)
    조건부 문서(spring.config.activate.* — on-profile 등 · 옛 spring.profiles)가 하나라도 있으면 exit 2 — 어느 문서가 실효인지 파일만으로 정할 수 없다
"""
import re
import sys

TARGETS = ("spring.config.import", "spring.config.location", "spring.config.additionallocation",
           "spring.profiles.active", "spring.profiles.include")
GROUP = "spring.profiles.group."


def norm(key):
    # 목록 첨자(spring.profiles.include[0])는 떼고 비교한다. 환경변수 꼴(SPRING_PROFILES_ACTIVE)은 파일 안에서는 그 키로 묶이지 않아(relaxed binding은 환경변수에만) 대상이 아니다
    key = re.sub(r"\[[^]]*\]", "", key)
    return ".".join(p.replace("-", "").replace("_", "").lower() for p in key.split("."))


def entries(node, prefix=""):
    """맵 항목마다 (점 표기 키, 값) — 목록 안의 맵도 같은 접두어로 내려간다"""
    if isinstance(node, dict):
        for k, v in node.items():
            key = f"{prefix}.{k}" if prefix else str(k)
            yield key, v
            yield from entries(v, key)
    elif isinstance(node, list):
        for v in node:
            yield from entries(v, prefix)


def flat(node, prefix=""):
    return (k for k, _ in entries(node, prefix))


def flat_values(node, prefix=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from flat_values(v, f"{prefix}.{k}" if prefix else str(k))
    elif not isinstance(node, list):
        yield prefix, node


CONDITIONAL_PREFIX = "spring.config.activate."   # norm 뒤 — on-profile · on-cloud-platform
LEGACY_PROFILES = "spring.profiles"              # Boot 2.4 전 문서 활성 키(spring.profiles.active·include·group과 다르다 — 정확히 이 키만)


def value(key, text):
    """YAML 문서들에서 [key]의 값(뒤 문서가 앞 문서를 덮는다 — Spring과 같은 순서). 없으면 None.
    조건부 문서(spring.config.activate.on-profile 등 · 옛 spring.profiles)가 있으면 ValueError — 그 문서가 켜질지 파일만으로 모른다(판정 불가)"""
    import yaml
    want, got = norm(key), None
    for doc in yaml.safe_load_all(text):
        if doc is None:
            continue
        # 옛 키는 값이 맵이 아닐 때만(spring.profiles: {active: …}는 spring.profiles.active의 부모일 뿐이다)
        cond = sorted({k for k, v in entries(doc)
                       if norm(k).startswith(CONDITIONAL_PREFIX) or (norm(k) == LEGACY_PROFILES and not isinstance(v, dict))})
        if cond:
            raise ValueError(f"조건부 문서 {cond}")
        for k, v in flat_values(doc):
            if norm(k) == want:
                got = v
    return got


def keys(path, text):
    if path.endswith(".properties"):
        out = []
        for line in text.splitlines():
            line = line.strip()
            if not line or line[0] in "#!":
                continue
            # 키 끝 = 첫 '='·':'·공백(java.util.Properties — 공백도 구분자다, 루프 3 재점검)
            cut = next((i for i, ch in enumerate(line) if ch in "=: \t\f"), len(line))
            out.append(line[:cut])
        return out
    import yaml
    return [k for doc in yaml.safe_load_all(text) if doc is not None for k in flat(doc)]


def main(path):
    try:
        found = sorted({k for k in keys(path, sys.stdin.read()) if norm(k) in TARGETS or norm(k).startswith(GROUP)})
    except Exception as e:   # 파싱 실패 — 확인 실패로 드러낸다
        print(f"cfgkeys: {path} 파싱 실패 {type(e).__name__}: {e}", file=sys.stderr)
        return 2
    if found:
        print(f"cfgkeys: {path} → {', '.join(found)}", file=sys.stderr)
    print(len(found))
    return 0


def main_value(key):
    try:
        v = value(key, sys.stdin.read())
    except Exception as e:   # 파싱 실패 — 확인 실패로 드러낸다
        print(f"cfgkeys: --value {key} 파싱 실패 {type(e).__name__}: {e}", file=sys.stderr)
        return 2
    print("" if v is None else (str(v).lower() if isinstance(v, bool) else str(v)))
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--value":
        sys.exit(main_value(sys.argv[2]))
    sys.exit(main(sys.argv[1]))
