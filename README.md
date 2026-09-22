# TipPop · 팁팝

**가까운 사람과 연결하고, 재화를 위로 밀어 전달하는 Android 전송 프로토타입.**

Kotlin과 Jetpack Compose 기반의 1인 개발 프로젝트입니다. NFC·BLE·QR로 상대방을 연결하고, 한 화면에서 금액을 선택해 반복 스와이프로 테스트 잔액을 전송합니다. 연결부터 잔액 동기화, 세션 종료, 거래내역과 테마별 수신 효과까지 하나의 사용자 흐름으로 구현했습니다.

- **담당 범위:** 요구사항 정리, UX·데이터 설계, Android 구현, Firebase 연동, 실기기 QA 및 개선
- **개발 방식:** AI 개발 도구를 활용한 1인 개발 및 실기기 검증
- **프로젝트 이름:** 앱은 TipPop, 저장소는 TipTransferDemo
- **상세 설명:** [포트폴리오 — 설계와 문제 해결](PORTFOLIO.md)

실제 결제·현금 충전·환전은 연동하지 않았습니다. 원화 표시는 테스트 잔액의 단위입니다. 근거리 통신은 상대방과 세션을 식별하고, 잔액 변경은 인터넷을 통해 Firestore에서 처리합니다.

## 사용자 흐름

1. 수신자가 받기 화면을 열어 전송 세션을 생성합니다.
2. 송신자는 기본 NFC 또는 BLE·QR로 수신자와 연결합니다.
3. 연결된 화면에서 슬라이더로 1회 금액을 선택합니다.
4. 위로 밀 때마다 선택 금액을 전송합니다. 1,000원으로 5번 밀면 총 5,000원입니다.
5. 수신 화면에 재화가 도착해 쌓이고 테마에 맞는 팡파레가 재생됩니다.
6. 종료 또는 세션 만료 후 누적 결과를 확인하고 추가 송수신이나 홈 이동을 선택합니다.

## 주요 기능

| 영역 | 구현 |
|---|---|
| 근거리 연결 | NFC Reader Mode/HCE, BLE 광고 기반 탐색, CameraX·ML Kit QR 스캔 |
| 잔액 처리 | Firestore Transaction으로 양측 잔액·일일 누적액·세션 누적값 갱신 |
| 연속 전송 | 제스처 반응과 서버 요청을 분리하고 대기열에서 순차 처리 |
| 세션 관리 | 연결 후 60초 유효시간, 종료·취소·만료, 추가 송수신 |
| 시각 경험 | 10종 UI 테마, 테마별 재화·배경, Lottie 수신 팡파레 |
| 사용자 기능 | 익명 인증, 사용자명 변경, 테스트 충전, 거래내역, 개인 전송 한도 |

## 기술 스택

| 용도 | 기술 |
|---|---|
| Android UI | Kotlin, Jetpack Compose, Material 3 |
| 인증·데이터 | Firebase Anonymous Authentication, Cloud Firestore |
| QR | CameraX, ML Kit Barcode Scanning, ZXing |
| 근거리 통신 | Android BLE, NFC/HCE·APDU |
| 비동기·동기화 | Kotlin Coroutines, Firestore Snapshot Listener |
| 모션 | Compose Animation, Lottie Compose 6.6.2 |
| 테스트·빌드 | JUnit, Gradle Wrapper, Android Studio, Git |

설정 기준은 `minSdk 29`, `compileSdk 37`, `targetSdk 37`, Gradle `9.5.0`입니다. 자세한 버전은 [버전 카탈로그](gradle/libs.versions.toml)와 [앱 빌드 설정](app/build.gradle.kts)을 참고하세요. 이전 3D 실험용 SceneView와 Konfetti 의존성도 남아 있으며, 현재 주요 재화 표현은 2D 에셋과 Lottie입니다.

## 데이터 흐름

```text
송신 앱 ── NFC / BLE / QR로 수신 세션 식별 ── 수신 앱
   │                                           │
   └── Firestore Transaction → Cloud Firestore ─┘
                                  │
                         Snapshot Listener
                                  ↓
                       잔액·수신 상태·효과 반영
```

`users`는 사용자와 잔액, `transferSessions`는 연결 상태와 누적 전송량, `transactions`는 거래내역을 관리합니다. 반복 전송 중에는 잔액과 세션 누적값을 갱신하고, 종료 처리에서 세션 합계 내역을 기록합니다. 별도 결제 서버 없이 클라이언트가 Firestore SDK로 트랜잭션을 실행하는 데모 구조입니다.

## 실행 방법

1. 저장소를 복제하고 Android Studio에서 엽니다.
2. Android SDK Platform 37과 프로젝트 Gradle에 호환되는 JDK를 준비합니다.
3. 본인의 Firebase 프로젝트에 Android 앱 `com.yunsi.tiptransferdemo`를 등록합니다.
4. `google-services.json`을 내려받아 `app/`에 배치합니다. 해당 파일은 Git에서 제외됩니다.
5. Firebase 익명 로그인을 활성화하고 Firestore 데이터베이스와 테스트에 맞는 접근 규칙을 설정합니다. 콘솔 설정은 저장소 복제로 복제되지 않습니다.
6. Gradle 동기화 후 실행합니다. NFC·BLE 검증에는 지원 Android 실기기 2대와 인터넷 연결을 사용합니다.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

APK 기본 경로: `app/build/outputs/apk/debug/app-debug.apk`.

종료 세션에 `cleanupAt`을 기록하며, 실제 자동 삭제에는 해당 필드를 대상으로 하는 Firestore TTL 정책을 별도로 활성화해야 합니다.

## 검증 및 범위

Galaxy S22·S25 Ultra에서 근거리 연결과 송수신 QA를 반복하며 권한 상태, 세션 만료, 시스템 바 겹침과 애니메이션 문제를 수정했습니다. 모션 경계·도착 좌표 등의 단위 테스트 소스가 포함되어 있습니다. 전체 기종 호환성과 정량적 지연 개선 수치는 검증하지 않았습니다.

실제 금전 서비스에는 서버 권한 기반 잔액 처리, 계정·결제·환전 및 운영 기능을 별도로 설계해야 합니다. 외부 모션 출처는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
