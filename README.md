# mediprice-batch

건강보험심사평가원(HIRA) 공공데이터 OpenAPI를 받아 Neon(PostgreSQL)에 저장하는 Spring Batch 프로젝트입니다.
MediPrice 서비스(비급여 진료비 비교)의 병원·비급여 항목·가격 데이터를 주 1회 동기화합니다.

## 기술 스택

- Java 21, Spring Boot 4.1.1, Spring Batch 6
- Neon PostgreSQL (PostGIS 확장 사용)
- HIRA OpenAPI (공공데이터포털, 응답은 XML)

## 동기화하는 데이터

| 테이블 | 내용 | 출처 API | 건수 (2026-10-14) |
|---|---|---|---|
| region_sido | 시도 코드 | 병원코드정보서비스 | 16 |
| region_sggu | 시군구 코드 | 병원코드정보서비스 | 256 |
| institution_class | 종별 코드 | 병원코드정보서비스 | 19 |
| npay_item | 비급여 항목 | 비급여진료비정보조회서비스 | 943 |
| hospital | 병원 기본정보 + 위치 | 병원정보서비스 | 79,865 |
| hospital_npay_price | 병원별 비급여 현재 가격 | 비급여진료비정보조회서비스 | 279,270 |
| npay_price_history | 월별 가격 이력 | (hospital_npay_price를 매달 복사) | 279,270 |
| hospital_detail | 병원 진료시간·주차·응급실 | 의료기관별상세정보서비스 | 4,000 |

## 시작하기

### 1. 준비

- Java 21 (`JAVA_HOME`이 21을 가리켜야 함. Gradle 9는 Java 17 이상 필요)
- 공공데이터포털 인증키: 병원정보서비스, 비급여진료비정보조회서비스, 의료기관별상세정보서비스, 병원코드정보서비스 4개 활용신청
- Neon DB에 테이블 생성: [`db/mediprice.sql`](db/mediprice.sql)을 Neon SQL Editor에 붙여넣고 **한 번만** 실행
  - 테이블 11개(배치 작업용 `*_seen` 2개, 미리 계산한 통계 `data_stats` 포함)와 PostGIS·pg_trgm 확장을 만듭니다. 다시 실행하면 "이미 있음" 오류가 납니다
  - 이미 만든 DB는 [`db/changes/`](db/changes)의 파일을 번호 순서대로 하나씩 적용합니다 (적용 시점·트랜잭션 여부·확인 쿼리는 각 파일 머리 주석. `CONCURRENTLY`/`VACUUM` 파일은 트랜잭션 밖에서 실행)
  - 확인 쿼리:
    ```sql
    SELECT table_name FROM information_schema.tables
    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
    ORDER BY table_name;
    ```
    테이블 11개가 보이면 성공입니다 (PostGIS가 만드는 `spatial_ref_sys`도 함께 보임)
    현재는 테이블이 생성된 상태 이므로 DB를 생성할 필요는 없습니다.

### 2. `.env` 파일 만들기

프로젝트 폴더에 `.env` 파일을 만들고 아래 값을 넣습니다. `.env`는 `.gitignore`에 포함되어 있어 커밋되지 않습니다.
`.env` 파일 제작 가이드라인

```
NEON_DB_URL=jdbc:postgresql://<호스트>/neondb?sslmode=require
NEON_DB_USERNAME=neondb_owner
NEON_DB_PASSWORD=<비밀번호>
HIRA_SERVICE_KEY=<공공데이터포털 일반 인증키 (Decoding)>
```

### 3. 실행

```powershell
# 전체 동기화
.\gradlew.bat bootRun

# 실패한 실행을 실패한 지점부터 이어서
.\gradlew.bat bootRun --args='--restart'

# 특정 Job만
.\gradlew.bat bootRun --args='--job=hiraCodeJob'
```

- 배치가 실패하면 종료 코드 1로 끝나고 Gradle이 `BUILD FAILED`를 표시합니다.
- Spring Batch 실행 기록 테이블(`batch_*`)은 처음 실행할 때 자동으로 만들어집니다.

## Job 구성

| Job | 포함 Step | 용도 |
|---|---|---|
| `hiraSyncJob` (기본) | 아래 13개 전체 | 주 1회 전체 동기화 |
| `hiraCodeJob` | sido → sggu → institutionClass | 코드 테이블만 갱신 |
| `priceHistoryJob` | priceHistory | 가격 이력만 다시 만들 때 |
| `hospitalDetailJob` | hospitalDetail → priceLastmod | 병원 상세만 다시 받을 때 |

### hiraSyncJob Step 순서

| 순서 | Step | 내용 | 방식 | 소요 시간 (실측) |
|---|---|---|---|---|
| 1 | sidoStep | 시도 코드 → region_sido | Tasklet | 5초 |
| 2 | sgguStep | 시군구 코드 → region_sggu (시도마다 호출) | Tasklet | 3초 |
| 3 | institutionClassStep | 종별 코드 → institution_class | Tasklet | 2초 |
| 4 | npayItemStep | 비급여 항목 → npay_item | Tasklet | 4초 |
| 5 | hospitalStep | 병원 → hospital | Chunk 1000건 | 약 6분 |
| 6 | npayPriceStep | 병원별 가격 → hospital_npay_price | Chunk 1000건 | 약 1시간 |
| 7 | syncCleanupStep | HIRA에서 사라진 행 삭제 | Tasklet | 6초 |
| 8 | priceHistoryStep | 현재 가격 → 이번 달 이력 (바뀐 행만) | Tasklet | 5초 |
| 9 | hospitalDetailStep | 가격 있는 병원마다 상세 호출 → hospital_detail | Chunk 100곳 | 4분 |
| 10 | detailCleanupStep | 가격이 없어진 병원의 상세 삭제 | Tasklet | 2초 |
| 11 | priceLastmodStep | 병원 상세의 사이트맵 lastmod(`price_lastmod`) 채우기 (바뀐 행만) | Tasklet | - |
| 12 | dataStatsStep | 홈 통계 `data_stats` 한 행 upsert. 실패하면 Job 실패 (다시 실행하면 이 Step부터) | Tasklet | - |
| 13 | vacuumStep | 큰 테이블 `VACUUM (ANALYZE)`. 실패해도 경고만 남기고 성공 | Tasklet (트랜잭션 없음) | - |

- Tasklet: 작은 데이터를 한 번에 받아 한 번에 저장
- Chunk: Reader가 읽고 Writer가 묶어서 저장·커밋하기를 반복. 실패해도 커밋된 부분은 남음
- 시간 대부분은 HIRA 응답 대기 시간입니다.

## 프로젝트 구조

```
db/
├── mediprice.sql                  새 DB용 테이블 생성 SQL (컬럼별 설명 주석 포함)
└── changes/                       이미 있는 DB에 번호 순서대로 적용할 변경 SQL
src/main/java/com/mediprice/batch/
├── BatchApplication.java       시작점. 배치 결과를 종료 코드로 반환
├── KoreaTime.java              Asia/Seoul 고정 (JVM 기본 시간대, Clock)
├── KoreaTimeConfig.java        Clock 빈 (Asia/Seoul)
├── HiraBatchRunner.java        실행기. requestedAt 파라미터, --restart, --job= 처리
├── hira/                       HIRA API 공통
│   ├── HiraProperties.java     hira.base-url, hira.service-key 설정값
│   ├── HiraClient.java         호출 → XML 읽기 → 오류 검사, 시간 제한·재시도
│   ├── HiraPage.java           응답 한 페이지 (item 목록 + 전체 건수)
│   ├── HiraApiException.java   HIRA 오류 예외 (코드 22, 30, PARSE, EMPTY 등)
│   └── HiraPagingItemReader.java  페이지 단위 공통 Reader. 처리한 페이지를 기록
└── job/                        Job·Step
    ├── HiraSyncJobConfig.java  Job·Step 등록과 실행 순서
    ├── SidoCodeTasklet.java / SgguCodeTasklet.java / InstitutionClassTasklet.java
    ├── NpayItemTasklet.java
    ├── HiraUpsertWriter.java   묶음 upsert 공통 Writer
    ├── HospitalWriter.java / NpayPriceWriter.java
    ├── StaleRowCleanupTasklet.java  갱신 안 된 행 삭제
    ├── PriceHistoryTasklet.java
    ├── PriceLastmodTasklet.java  병원 상세 price_lastmod 채우기
    ├── DataStatsTasklet.java   data_stats 한 행 upsert
    ├── VacuumTasklet.java      마지막 VACUUM (ANALYZE)
    ├── PricedHospitalReader.java
    ├── HospitalDetailProcessor.java
    └── HospitalDetailWriter.java
```

## 테이블 구조

자세한 컬럼 설명은 [`db/mediprice.sql`](db/mediprice.sql)의 주석에 있습니다.

| 테이블 | 키 | 비고 |
|---|---|---|
| hospital | `ykiho` | 병원 고유키 (병원정보·비급여·상세정보 API 공통). `geog`는 경도·위도로 자동 계산 |
| hospital_detail | `ykiho` → hospital | 세부정보 API 응답을 `detail`(jsonb)에 통째로 저장 |
| npay_item | `npay_cd` | 비급여 항목 (중분류 > 소분류 > 상세분류) |
| hospital_npay_price | (`ykiho`, `sno`) | `ykiho` → hospital, `npay_cd` → npay_item. 병원이 삭제되면 함께 삭제 |
| npay_price_history | (`ykiho`, `npay_cd`, `snapshot_month`, `sno`) | FK 없음: 병원이 폐업해도 과거 가격 기록은 유지. PK가 병원·항목별 추이 조회 인덱스를 겸함 |
| data_stats | id = 1 한 행 | 홈 화면 통계(가격 있는 병원 수·항목 수·가격 수·데이터 기준일). 배치가 가격 동기화 뒤 채우고 백엔드가 읽음 |
| hospital_seen / hospital_npay_price_seen | 없음 | 배치 작업용. 이번 실행에서 HIRA 목록에 보인 키. 정리 Step이 비움 |
| region_sido / region_sggu | `sido_cd` / `sggu_cd` | region_sggu.sido_cd → region_sido |
| institution_class | `cl_cd` | |

- `hospital.sido_cd`, `cl_cd`에는 FK를 걸지 않았습니다. 코드표에 없는 값(광주 `240000`, 정신병원 `29`)이 실제로 옵니다.

## 동작 원칙

- **upsert:** 모든 저장은 키가 같으면 덮어쓰는 upsert입니다. 여러 번 실행해도 중복이 생기지 않습니다.
- **바뀐 행만 쓰기:** 병원(`hospital`)·가격(`hospital_npay_price`)·가격 이력은 값이 바뀐 행만 다시 씁니다. 값이 같은 행까지 매주 다시 쓰면 28만 행이 새로 기록돼 테이블·인덱스가 불어나고 Neon 저장 비용(WAL)이 늘기 때문입니다. 그래서 이 두 테이블의 `synced_at`은 "마지막으로 값이 바뀐 시각"이고, 이번 실행에서 본 키는 `hospital_seen`, `hospital_npay_price_seen`에 따로 적습니다.
- **사라진 행 삭제:** 정리 Step은 두 번 연속 안 보인 행을 "HIRA에서 사라진 데이터"로 보고 삭제합니다. 코드 테이블·비급여 항목·병원 상세는 직전 성공 실행의 기준 시각보다 `synced_at`이 오래된 행을, 병원·가격은 이번 실행에 안 보여 `missed_since`가 표시된 행 중 직전 성공 실행 때도 이미 안 보였던 행을 지웁니다. 그래서 실제로 없어진 데이터는 한 번 더 남아 있습니다. 정리 Step이 성공하면 `*_seen`을 비웁니다. 이번 실행에서 본(갱신된) 행이 0건인 테이블과 직전 성공 실행이 없는 경우에는 삭제를 건너뜁니다. 다른 테이블이 참조 중인 행(가격이 남은 병원·비급여 항목, 시군구가 남은 시도)은 지우지 않고 경고 로그를 남깁니다.
  - 이 버전에서 예전 배치 코드로 되돌렸다가 다시 올릴 때는, 다시 올린 첫 실행 전에 `UPDATE hospital SET missed_since = NULL WHERE missed_since IS NOT NULL;`, `UPDATE hospital_npay_price SET missed_since = NULL WHERE missed_since IS NOT NULL;`, `TRUNCATE hospital_seen, hospital_npay_price_seen;`을 실행합니다. 예전 배치는 `missed_since`를 지우지 않아 그 사이 다시 나타난 행이 한 번만 안 보여도 지워질 수 있습니다.
- **원본 미보관:** API 응답 원본은 저장하지 않습니다(읽는 곳이 없고 가격 테이블의 75%를 차지했음). 필요한 값은 컬럼으로 둡니다.
- **XML 사용:** JSON 응답은 `"01"` 같은 코드값의 앞자리 0이 사라져서 XML로 받습니다.
- **전체 건수 검증:** 페이지 Reader는 읽은 건수가 HIRA의 `totalCount`와 다르면 Step을 실패시킵니다. 읽는 도중이나 재시작 사이에 `totalCount`가 바뀌면 1페이지부터 다시 읽습니다 (최대 3번). 정리 Step은 `hospital`, `hospital_npay_price`에서 이번 실행에 본 키 수(`*_seen`, 중복 제외)가 `totalCount`보다 적으면 삭제하지 않고 실패합니다.

## 실패와 이어서 실행

- 실행할 때마다 실행 기준 시각 `requestedAt`을 파라미터로 넣습니다.
- `--restart`는 마지막으로 실패한 실행의 파라미터를 그대로 다시 씁니다. Spring Batch가 이를 같은 실행의 재시작으로 보고 완료된 Step을 건너뜁니다.
- 프로그램이 갑자기 꺼져 실행 기록이 "실행 중"으로 남은 경우도 실패로 정리한 뒤 이어서 실행합니다.
- 실행 중 PC가 절전에 들어가면 프로세스가 멈추고 연결이 끊겨 Step이 실패합니다. 실행 중에는 전원 버튼을 누르거나 덮개를 닫지 않습니다. 실패하면 `--restart`로 이어서 실행합니다.
- 페이지 Reader와 병원 상세 Reader는 커밋할 때마다 처리 위치를 실행 기록에 남겨, 재시작하면 그 위치부터 읽습니다.
- 정리 Step이 "갱신된 행이 전체 건수보다 적음"으로 실패하면 `--restart`로 복구되지 않습니다. 완료된 Step을 건너뛰어 같은 결과가 나오므로 새로 실행해야 합니다.
- 가격 Step이 FK 오류(`violates foreign key constraint`)로 실패하면 `--restart`로 복구되지 않습니다. 가격 목록에 병원·비급여 항목 목록에 아직 없는 병원이나 코드가 나온 경우인데, 완료된 병원·항목 Step을 다시 읽지 않아 같은 오류가 납니다. 새로 실행하고, 그래도 같은 오류가 나면 HIRA 목록이 아직 어긋난 상태이므로 시간을 두고 다시 새로 실행합니다.
- 같은 Job의 다른 실행이 진행 중이면 새 실행과 `--restart` 모두 시작하지 않고 종료 코드 1로 끝납니다. 실행 기록이 30분 넘게 갱신되지 않으면 프로그램이 꺼진 것으로 봅니다. 그래서 비정상 종료 직후 30분 동안은 `--restart`도 막힙니다.

예: 2026-10-14 가격 Step이 242,000건 저장 후 DB 연결이 끊겨 실패 → `--restart`로 243페이지부터 이어서 9분 만에 완료 (처음부터였다면 약 1시간).

## 주의사항

- **호출 한도:** 공공데이터포털 개발계정은 API 기능별 하루 10,000건입니다. 병원 상세 Step이 한 번에 약 4,000건을 쓰므로 전체 실행은 하루 2번까지 가능합니다.
- **HIRA 오류 코드:** 22(호출 한도 초과)와 30(키 오류)은 재시도하지 않고 바로 실패합니다. 네트워크 오류·시간 초과·일시 오류는 최대 3번까지 호출합니다.
- **포털 장애:** 포털이 302로 응답하면 로그에 `빈 응답 HTTP 302 Location=...`이 남습니다 (인증키가 남지 않게 쿼리는 뺌). 복구 후 `--restart`로 이어서 실행하면 됩니다.
- **HIRA 응답 지연:** HIRA가 느려지면 오류 코드 05(서비스 연결실패)가 나고, 같은 페이지에서 3번 연속 나면 Step이 실패합니다. 병원 Step이 평소(6~7분)보다 크게 느리면 이 상태이므로 시간을 두고 `--restart`로 이어서 실행합니다. 호출마다 붙는 기본 대기 시간이 늘어난 것이라 페이지 크기(`numOfRows`)를 줄여도 빨라지지 않습니다.
- **병원 상세 누락:** 가격이 있는 병원 중 약 38%는 HIRA 세부정보 API가 0건을 반환해 `hospital_detail.detail`이 비어 있습니다 (HIRA 원본에 없는 데이터).
- **기준 월:** 가격 이력의 `snapshot_month`는 `requestedAt`이 속한 달의 1일입니다. 같은 달에 다시 실행하면 그 달 기록을 현재 가격에 맞춥니다(바뀐 행만 고치고, 새 행은 추가, 현재 가격에 없는 행은 삭제).
- **VACUUM:** 마지막 Step에서 `hospital_npay_price`, `hospital`, `npay_price_history`, `hospital_detail`을 `VACUUM (ANALYZE)` 합니다. Neon은 compute가 다시 시작되면 통계가 초기화돼 autovacuum이 대량 갱신을 놓칠 수 있기 때문입니다. 다른 VACUUM이 잡고 있으면 건너뛰고, 실패해도 배치는 성공으로 끝납니다.
- **시간대:** 배치는 호스트의 `TZ`와 관계없이 Asia/Seoul 기준으로 동작합니다. 시작할 때 JVM 기본 시간대를 Asia/Seoul로 고정하므로(`KoreaTime`) 시간대가 없는 실행 기록(`batch_*`)과 `requestedAt`은 한국 시각이고, 정리 기준 시각도 Asia/Seoul로 해석해 `synced_at`(timestamptz)과 비교합니다. 자동 실행에 `TZ=Asia/Seoul`을 지정할 필요가 없습니다. 이전에 UTC 호스트에서 쌓인 `batch_*` 기록은 한국 시각으로 읽혀 9시간 이르게 보이므로, 전환 직후 한 번은 정리가 평소보다 덜 삭제됩니다(과삭제는 없음). 이전 호스트가 이미 KST였다면 영향이 없습니다. 실행 중이 아닐 때 배포하세요.
