# TipTransferDemo 인수인계 문서

## 새 작업자에게 전달할 내용

이 프로젝트는 Android Jetpack Compose 기반의 QR P2P 테스트 재화 전송 데모입니다.

프로젝트 폴더 전체를 Android Studio에서 열고, 이 문서를 먼저 읽은 다음 작업을 이어가세요.

## 현재 구현 완료 범위

- Firebase Anonymous Authentication으로 사용자 생성
- 최초 잔액 100개 및 테스트 재화 충전
- 하나의 앱에서 보내기/받기 모두 지원
- 수신자가 QR을 열고 송신자가 스캔하는 구조
- 에뮬레이터용 세션 ID 직접 입력 대체 경로
- QR 스캔 성공 뒤 60초 수신 세션 시작
- 수신 대기, 취소, 만료, 백그라운드 이탈 처리
- 1회 최대 10개, 하루 최대 100개 전송 제한
- Firestore 트랜잭션으로 송신/수신 잔액 및 거래 기록 동시 반영
- 송신 상승, 수신 낙하, 완료 반짝임 효과
- 효과 테마 선택: 기본 보라 / 골드 코인 / 파티클
- 골드 코인 테마는 별이 새겨진 동전이 수량만큼 순차 회전·이동하도록 작업 중

## 프로젝트 핵심 파일

- `app/src/main/java/com/example/tiptransferdemo/MainActivity.kt`
  - 홈 화면, 로그인, 잔액, 테스트 충전, 테마 선택
- `app/src/main/java/com/example/tiptransferdemo/SendScannerScreen.kt`
  - QR 스캔, 수량 선택, 전송 제스처, 전송 효과
- `app/src/main/java/com/example/tiptransferdemo/ReceiveScreen.kt`
  - 수신 QR, 수신 대기, 만료, 수신 효과
- `app/src/main/java/com/example/tiptransferdemo/VisualTheme.kt`
  - 테마 정의
- `app/google-services.json`
  - Firebase 프로젝트 설정 파일

## Firebase 상태

- Firebase 프로젝트: `tip-transfer-demo`
- Firestore 위치: `asia-northeast3`
- Firestore: Standard 버전
- Anonymous Authentication 활성화 완료

다른 PC나 다른 Firebase 계정에서 작업할 경우에는 Firebase 콘솔 접근 권한이 필요합니다. 접근 권한이 없다면 별도 Firebase 프로젝트를 만들고 `google-services.json`을 교체해야 합니다.

## 현재 동작 정책

- 수신자가 QR을 열면 세션 상태는 `active`
- 송신자가 QR을 스캔하면 상태는 `claimed`, 이때부터 60초 유효
- 전송 완료 시 상태는 `used`
- 수신 취소, 뒤로가기, 홈 이동, 백그라운드 전환 시 `cancelled`
- 시간 초과 시 `expired`

## 검증 방법

1. Android Studio에서 프로젝트를 연다.
2. 에뮬레이터 두 대를 실행한다.
3. 두 기기에서 앱을 실행한다.
4. 한쪽에서 `받기`를 열어 QR/세션을 생성한다.
5. 다른 쪽에서 `보내기`를 열고 QR을 스캔한다.
   - 에뮬레이터 카메라가 어려우면 Firestore `transferSessions` 문서 ID를 직접 입력한다.
6. 수량을 선택하고 위로 밀어 전송한다.
7. 양쪽 잔액, 수신 대기 전환, 완료 애니메이션, Firestore 기록을 확인한다.

## 주의 사항

- 현재는 데모용 클라이언트 Firestore 트랜잭션 구조다.
- 실제 현금성 재화 서비스로 확장할 때는 잔액 변경과 한도 검증을 서버(예: Cloud Functions)에서 처리해야 한다.
- 오래된 `used`, `expired`, `cancelled` 세션의 자동 정리 정책은 아직 구현하지 않았다.
- 최근 거래 내역은 현재 홈에서 실제 목록으로 표시하지 않는다.

## 다음 작업 권장 순서

1. 골드 코인 테마 효과를 실제 기기에서 검증하고 속도·간격 조정
2. 로켓 테마: 송신 시 발사, 수신 시 착륙
3. 파티클 테마: 송신 시 분산, 수신 시 재조립
4. 홈의 최근 송금/수신 내역 표시
5. 세션 정리 및 Firestore 보안 규칙 강화

## 새 ChatGPT/Codex 대화에 붙여 넣을 요청문

`첨부한 TipTransferDemo 프로젝트와 AI_HANDOFF.md를 읽고 기존 작업을 이어가 주세요. 먼저 현재 코드 상태를 확인한 뒤, 빌드 오류가 없는지 점검하고 다음 작업인 골드 코인 테마 효과 검증 및 개선부터 진행해 주세요. 기존 기능을 깨지 않도록 작은 단위로 수정하고, Android Studio에서 실행할 수 있도록 안내해 주세요.`
