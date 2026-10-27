<p align="center">
  <img src="../assets/logo.webp" alt="MarkText for Android 로고" width="96" height="96">
</p>

<h1 align="center">MarkText for Android</h1>

<p align="center">
  <sub>
    🌐&nbsp;
    <a href="../../README.md">English</a>
    &nbsp;·&nbsp; <a href="README.zh-CN.md">简体中文</a>
    &nbsp;·&nbsp; <a href="README.zh-TW.md">繁體中文</a>
    &nbsp;·&nbsp; <a href="README.de.md">Deutsch</a>
    &nbsp;·&nbsp; <a href="README.es.md">Español</a>
    &nbsp;·&nbsp; <a href="README.fr.md">Français</a>
    &nbsp;·&nbsp; <a href="README.ja.md">日本語</a>
    &nbsp;·&nbsp; <b>한국어</b>
    &nbsp;·&nbsp; <a href="README.pt.md">Português</a>
    &nbsp;·&nbsp; <a href="README.tr.md">Türkçe</a>
  </sub>
</p>

<p align="center">
  <em>조용히, 나만의 Markdown.</em>
</p>

<p align="center">
  <sub>Android를 위한 군더더기 없는 Markdown 편집기.</sub>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-MIT-4c566a?style=flat-square" alt="라이선스: MIT">
  &nbsp;
  <img src="https://img.shields.io/badge/Android-7.0%2B-4c8492?style=flat-square&logo=android&logoColor=white" alt="Android 7.0+">
  &nbsp;
  <a href="https://github.com/fharenheit/marktext-android/releases/latest"><img src="https://img.shields.io/github/v/release/fharenheit/marktext-android?style=flat-square&color=c98a4b&label=release" alt="최신 릴리스"></a>
</p>

<p align="center">
  <a href="#주요-기능">주요 기능</a> ·
  <a href="#새로운-기능">새로운 기능</a> ·
  <a href="#소스에서-빌드하기">소스에서 빌드하기</a> ·
  <a href="#라이선스-및-저작자-표시">라이선스</a>
</p>

<p align="center">
  <img src="../screenshots/editor-light.webp" alt="Markdown 문서 편집 — 라이트 테마" width="240">
  &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;
  <img src="../screenshots/editor-dark.webp" alt="다크 테마로 본 같은 문서" width="240">
</p>

<p align="center">
  <sub>☀&nbsp; 라이트 &nbsp;·&nbsp; 다크 &nbsp;☾&nbsp; — 시스템 설정을 그대로 따릅니다</sub>
</p>

> [!NOTE]
> **비공식 커뮤니티 포트** — MarkText 팀과 관련이 없으며, 팀의 승인이나 유지
> 관리를 받지 않습니다. MarkText의 오픈 소스 편집기 코어(Muya)를 기반으로
> Android에 맞게 수정했습니다. [라이선스 및 저작자 표시](#라이선스-및-저작자-표시)를
> 참고하세요.

## 소개

MarkText for Android는 MarkText의 실시간 미리 보기 Markdown 편집을 폰으로
가져옵니다. 편집기는 MarkText의 오픈 소스 코어인 Muya로, 모바일에 맞게 크게
손질했습니다. 큰 문서에서 더 빠르게 동작하고, 폰 화면 너비에 맞춰 다시
배치했으며, 터치 선택과 도구 모음을 더했습니다. 작성한 내용은 데스크톱과 같은
품질로 렌더링되며, 이 모든 것이 한 손을 위해 설계된 인터페이스 안에서
이루어집니다.

## 주요 기능

### 한 글자도 잃지 않는 가벼운 Markdown 편집기

<table width="100%">
<tr>
<td valign="middle">

- 진짜 실시간 미리 보기(WYSIWYG) 편집.

- CommonMark와 GitHub Flavored Markdown 완전 지원: 수식(KaTeX), 표, 각주,
  front matter, 다이어그램, 구문 강조 코드까지.

- 긴 파일에서도 부드럽게 동작하는 문서 개요와 편집기 내 검색.

- 수식, 코드 강조, 글꼴까지 모두 담아 **PDF**로 내보내기.

- **작업을 절대 잃지 않습니다.** 자동 저장, 복구 초안, 원자적 쓰기가 모든
  변경 사항을 지킵니다.

- **기본이 프라이버시.** 계정이 필요 없고 원격 측정도 없습니다. 클라우드
  드라이브, GitHub, Git 원격 저장소에서 연 문서가 아니면 모든 것이 기기 안에
  머뭅니다.

- **가볍습니다.** Vue + Capacitor 셸을 사용해 Git 엔진과 내장 글꼴을 포함해도
  앱 전체가 약 15 MB입니다.

</td>
<td width="220" valign="top"><img src="../screenshots/editor-rich.webp" alt="입력하는 동안 실시간으로 렌더링되는 표, 코드, 수식" width="200"></td>
</tr>
</table>

### 나만의 편집기로

<table width="100%">
<tr>
<td width="220" valign="middle"><img src="../screenshots/makeityours.webp" alt="편집 중 표시되는 하단 고정 서식 도구 모음과 사용자 정의 선택(붙여넣기) 막대" width="200"></td>
<td valign="middle">

- **나만의 도구 모음 만들기.** 명령 풀에서 하단 빠른 도구 막대를 구성하고
  드래그로 순서를 바꿉니다. 선택 도구 모음에도 원하는 명령을 담을 수 있습니다.

<br>

- **테마와 외관.** 라이트, 다크에 더해 30가지가 넘는 사용자 정의 테마를 고르고, 글자와 레이아웃을
  조정할 수 있습니다.

<br>

- **취향대로 쓰는 Markdown.** 목록 기호부터 front matter까지, Markdown이
  쓰이고 렌더링되는 방식을 세밀하게 조정할 수 있습니다.

<br>

- **파일 단위 제어.** 문서별 인코딩, 줄 끝, 끝 줄바꿈 처리.

</td>
</tr>
</table>

### 폰을 위해 만들고, 모두를 위해 다듬었습니다

<table width="100%">
<tr>
<td width="220" valign="top"><img src="../screenshots/cjk.webp" alt="중국어 인터페이스로 중국어 문서를 표시한 편집기" width="200"></td>
<td valign="middle">

- **파일은 제자리에 그대로.** 시스템 선택기를 통해 어떤 저장소 제공자에서든
  `.md`를 바로 편집하고, 공유 시트로 다른 앱과 문서를 주고받습니다.

<br>

- **엄지를 위한 설계.** 한 손으로 편안하게 닿는 배치와 차분한 편집기 중심
  레이아웃.

<br>

- **접근성 있게, 절제되게.** WCAG 2.2 AA를 충족하는 조용한 그래파이트 디자인,
  뚜렷한 포커스 순서, 그리고 차분하고 절제된 모션.

<br>

- **열 가지 언어**를 시스템 설정에 따라 자동으로 선택합니다: 영어, 독일어,
  스페인어, 프랑스어, 일본어, 한국어, 포르투갈어, 터키어, 그리고 중국어 간체와
  번체.

</td>
</tr>
</table>

### 폰에서 쓰는 Git과 GitHub

- **GitHub 문서를 열고, 편집하고, 커밋합니다.** 디바이스 플로나 개인 액세스
  토큰으로 로그인해 저장소와 브랜치를 둘러보고, 명시적인 커밋으로 저장합니다.
  새 브랜치에 커밋하고 Pull Request를 열 수도 있습니다. 비공개 저장소의
  이미지도 표시됩니다.

- **기기 안의 Git 작업 사본.** GitHub나 HTTPS Git 호스트에서 클론해 오프라인으로
  편집하고, diff 확인, 되돌리기, 커밋 & 푸시를 한 번에, 파일별 충돌 해결이 되는
  풀, 브랜치 전환·생성, 히스토리 보기까지 지원합니다.

- **소스와 WYSIWYG를 나란히.** 분할선을 끌어 조절하는 양방향 분할 보기와
  3-way 병합을 쓰는 "최신 내용 풀 & 병합". 저장할 때 손대지 않은 줄은 문서
  원래의 Markdown 서식을 그대로 유지합니다.

- **D2Coding Ligature**를 기본 고정폭·소스 글꼴로 내장했습니다.

---

## 새로운 기능

업스트림 v0.2.1에서 fork한 이후의 변경 사항입니다. 전체 노트는
[Releases](https://github.com/fharenheit/marktext-android/releases) 페이지에
있습니다.

### v0.3.1 — 버그 수정

- **데이터 보호.** 저장소를 삭제할 때 심볼릭 링크를 따라가지 않습니다. 병합을
  중단하거나 완료해도 관련 없는 커밋 안 된 수정은 그대로 둡니다. 분할 보기,
  파일 패널, 서식 유지 저장에서 편집이 사라지지 않습니다.
- **보안.** 로그인 토큰, 저장된 Git 비밀번호, 작업 사본을 Android 백업에서
  제외합니다.
- **Git.** 푸시로 새 브랜치를 게시할 수 있고, 커밋 & 푸시가 중복 실행되지 않는
  한 번의 작업으로 동작합니다. 네트워크 작업에 타임아웃이 있어 자동 저장을 막지
  않으며, 병합이 CRLF 줄바꿈을 유지하고, 풀 실패 시 파일을 원래 인코딩으로
  복원합니다.
- **GitHub.** 보호 브랜치, SSO, 권한 부족, 속도 제한 거부를 정확히 안내합니다.
  커밋이 반영된 브랜치는 롤백하지 않고, 커밋 후 이미지 캐시를 갱신하며, 열 수
  없는 공유 링크는 텍스트 초안으로 가져옵니다.
- **다듬기.** 저장소 오류 메시지 10개 언어 지원, 접근성과 터치 영역을 개선한
  분할선, 큰 문서에서 더 가벼운 줄 비교.

### v0.3.0 — Git과 GitHub

- GitHub 문서: 열기, 커밋, 새 브랜치와 Pull Request, 저장소 이미지, 공유된
  github.com 링크 열기.
- 기기 안의 Git 작업 사본: 클론, diff, 되돌리기, 커밋, 푸시, 풀과 충돌 해결,
  브랜치, 히스토리, 파일 패널.
- 양방향 소스/WYSIWYG 분할 보기, 최신 내용 풀 & 병합, 서식을 유지하는 저장.
- 표, IME Enter, 인라인 SVG 수정. D2Coding Ligature 내장, 본문은 시스템 글꼴,
  고정폭·소스 글꼴은 별도 설정.

## 프로젝트 현황

> [!IMPORTANT]
> 릴리스는 저장소의 릴리스 워크플로로 빌드됩니다. 릴리스 키스토어가 설정되기
> 전까지는 **디버그 서명**되며, 업스트림 앱과 별개의 앱
> (`io.github.renakoni.marktextandroid.debug`)으로 설치됩니다. v0.3.0과 v0.3.1은
> 각각 일회성 키로 서명되었으므로, v0.3.0 위에 업데이트하지 말고 새로
> 설치하세요.

빌드는
[Releases](https://github.com/fharenheit/marktext-android/releases/latest)
페이지에서 내려받으세요.

## 소스에서 빌드하기

[Node.js](https://nodejs.org/)와 [pnpm](https://pnpm.io/), 그리고
[Android Studio](https://developer.android.com/studio)(Android SDK와 JDK)가
필요합니다. 앱은 API 24 이상에서 실행되며, API 36을 기준으로 빌드됩니다.

```sh
pnpm install          # 의존성 설치
pnpm dev              # 브라우저에서 웹 셸 미리 보기
pnpm android:sync     # 웹 앱을 빌드해 Android 프로젝트로 동기화
pnpm android:open     # Android Studio로 열고 기기 또는 에뮬레이터에서 실행
```

다른 스크립트(`test`, `lint`, `typecheck`, `build`)는 `package.json`에
있습니다. 릴리스 관리자는 [`docs/RELEASING.md`](../RELEASING.md)를 따르세요.

> [!TIP]
> Markdown 편집기 코어는 `third_party/muya` 아래에 벤더링된, **수정된**
> `@muyajs/core`(Muya) 사본입니다. 이를 변경했다면 빌드 전에 수정 사항을
> `node_modules/@muyajs/core/src/**`로 동기화하세요 — 계약 테스트가 어긋남을
> 잡아냅니다.

## 기여하기

이슈와 풀 리퀘스트를 환영합니다. 변경 사항은 한 가지에 집중하고, 필요한
곳에는 테스트도 함께 추가해 주세요.

## 라이선스 및 저작자 표시

MarkText for Android는 [MIT 라이선스](../../LICENSE)로 배포됩니다.

이 앱은 MarkText의 오픈 소스 작업 위에 만들어진 **비공식** 포트로, MarkText
프로젝트와 관련이 없으며 승인을 받지 않았습니다:

- **MarkText** — 이 포트가 따르는 데스크톱 편집기이자 디자인. Copyright © Luo
  Ran 및 MarkText 기여자, MIT 라이선스.
- **Muya** (`@muyajs/core`) — 편집기 코어. `third_party/muya` 아래에 벤더링해
  수정했으며, 원본 MIT 라이선스를 유지합니다
  ([`third_party/muya/LICENSE`](../../third_party/muya/LICENSE)).

## 감사의 말

MarkText for Android는 수많은 오픈 소스 작업 위에 서 있습니다:
[MarkText](https://github.com/marktext/marktext) 편집기와 그
[기여자들](https://github.com/marktext/marktext/graphs/contributors),
[Muya](https://github.com/marktext/muya) 편집 엔진, 그리고
[Vue](https://vuejs.org/), [Vite](https://vite.dev/),
[Capacitor](https://capacitorjs.com/). 이 모든 것을 만들어 주신 분들께
감사드립니다.

---

<p align="center"><sub><em>조용히, 나만의 Markdown.</em></sub></p>
