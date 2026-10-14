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
- Neon DB에 테이블 생성: [`db/schema.sql`](db/schema.sql)을 Neon SQL Editor에 붙여넣고 **한 번만** 실행
  - 테이블 8개와 PostGIS 확장을 만듭니다. 다시 실행하면 "이미 있음" 오류가 납니다
  - 확인 쿼리:
    ```sql
    SELECT table_name FROM information_schema.tables
    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
    ORDER BY table_name;
    ```
    테이블 8개가 보이면 성공입니다 (PostGIS가 만드는 `spatial_ref_sys`도 함께 보임)

### 2. `.env` 파일 만들기

프로젝트 폴더에 `.env` 파일을 만들고 아래 값을 넣습니다. `.env`는 `.gitignore`에 포함되어 있어 커밋되지 않습니다.

```
NEON_DB_URL=jdbc:postgresql://<호스트>/neondb?sslmode=require
NEON_DB_USERNAME=neondb_owner
NEON_DB_PASSWORD=<비밀번호>
HIRA_SERVICE_KEY=<공공데이터포털 일반 인증키 (Decoding)>
```

| 키 | 설명 |
|---|---|
| `NEON_DB_URL` | Neon Connect 창의 주소에서 `postgresql://` 앞에 `jdbc:`를 붙이고, `user:password@` 부분과 `channel_binding` 옵션은 뺀 값 |
| `NEON_DB_USERNAME`, `NEON_DB_PASSWORD` | Neon 접속 계정 |
| `HIRA_SERVICE_KEY` | 공공데이터포털 인증키. 4개 API가 같은 키를 씀 |
| `HIRA_BASE_URL` (선택) | 테스트 서버를 쓸 때만. 기본값 `https://apis.data.go.kr/B551182` |

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
| `hiraSyncJob` (기본) | 아래 10개 전체 | 주 1회 전체 동기화 |
| `hiraCodeJob` | sido → sggu → institutionClass | 코드 테이블만 갱신 |
| `priceHistoryJob` | priceHistory | 가격 이력만 다시 만들 때 |
| `hospitalDetailJob` | hospitalDetail | 병원 상세만 다시 받을 때 |

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
| 8 | priceHistoryStep | 현재 가격 → 이번 달 이력 | Tasklet | 5초 |
| 9 | hospitalDetailStep | 가격 있는 병원마다 상세 호출 → hospital_detail | Chunk 100곳 | 4분 |
| 10 | detailCleanupStep | 가격이 없어진 병원의 상세 삭제 | Tasklet | 2초 |

- Tasklet: 작은 데이터를 한 번에 받아 한 번에 저장
- Chunk: Reader가 읽고 Writer가 묶어서 저장·커밋하기를 반복. 실패해도 커밋된 부분은 남음
- 시간 대부분은 HIRA 응답 대기 시간입니다.

## 프로젝트 구조

```
db/
└── schema.sql                  테이블 8개 생성 SQL (컬럼별 설명 주석 포함)
src/main/java/com/mediprice/batch/
├── BatchApplication.java       시작점. 배치 결과를 종료 코드로 반환
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
    ├── PricedHospitalReader.java
    ├── HospitalDetailProcessor.java
    └── HospitalDetailWriter.java
```

## 테이블 구조

자세한 컬럼 설명은 [`db/schema.sql`](db/schema.sql)의 주석에 있습니다.

| 테이블 | 키 | 비고 |
|---|---|---|
| hospital | `ykiho` | 병원 고유키 (병원정보·비급여·상세정보 API 공통). `geog`는 경도·위도로 자동 계산 |
| hospital_detail | `ykiho` → hospital | 세부정보 API 응답을 `detail`(jsonb)에 통째로 저장 |
| npay_item | `npay_cd` | 비급여 항목 (중분류 > 소분류 > 상세분류) |
| hospital_npay_price | (`ykiho`, `sno`) | `ykiho` → hospital, `npay_cd` → npay_item. 병원이 삭제되면 함께 삭제 |
| npay_price_history | (`snapshot_month`, `ykiho`, `sno`) | FK 없음: 병원이 폐업해도 과거 가격 기록은 유지 |
| region_sido / region_sggu | `sido_cd` / `sggu_cd` | region_sggu.sido_cd → region_sido |
| institution_class | `cl_cd` | |

- `hospital.sido_cd`, `cl_cd`에는 FK를 걸지 않았습니다. 코드표에 없는 값(광주 `240000`, 정신병원 `29`)이 실제로 옵니다.

## 동작 원칙

- **upsert:** 모든 저장은 `INSERT ... ON CONFLICT DO UPDATE`. 여러 번 실행해도 중복이 생기지 않습니다.
- **synced_at:** 모든 행에 저장 시각을 기록합니다. 정리 Step은 실행 기준 시각보다 오래된 행을 "HIRA에서 사라진 데이터"로 보고 삭제합니다. 이번 실행에서 갱신된 행이 0건인 테이블은 삭제를 건너뜁니다.
- **원본 보관:** API 응답 원본은 `raw`(jsonb) 컬럼에 저장합니다.
- **XML 사용:** JSON 응답은 `"01"` 같은 코드값의 앞자리 0이 사라져서 XML로 받습니다.
- **전체 건수 검증:** 페이지 Reader는 읽은 건수가 HIRA의 `totalCount`와 다르면 Step을 실패시킵니다.

## 실패와 이어서 실행

- 실행할 때마다 실행 기준 시각 `requestedAt`을 파라미터로 넣습니다.
- `--restart`는 마지막으로 실패한 실행의 파라미터를 그대로 다시 씁니다. Spring Batch가 이를 같은 실행의 재시작으로 보고 완료된 Step을 건너뜁니다.
- 프로그램이 갑자기 꺼져 실행 기록이 "실행 중"으로 남은 경우도 실패로 정리한 뒤 이어서 실행합니다.
- 페이지 Reader와 병원 상세 Reader는 커밋할 때마다 처리 위치를 실행 기록에 남겨, 재시작하면 그 위치부터 읽습니다.

예: 2026-10-14 가격 Step이 242,000건 저장 후 DB 연결이 끊겨 실패 → `--restart`로 243페이지부터 이어서 9분 만에 완료 (처음부터였다면 약 1시간).

## 주의사항

- **호출 한도:** 공공데이터포털 개발계정은 API 기능별 하루 10,000건입니다. 병원 상세 Step이 한 번에 약 4,000건을 쓰므로 전체 실행은 하루 2번까지 가능합니다.
- **HIRA 오류 코드:** 22(호출 한도 초과)와 30(키 오류)은 재시도하지 않고 바로 실패합니다. 네트워크 오류·시간 초과·일시 오류는 최대 3번 재시도합니다.
- **포털 장애:** 포털이 302로 응답하면 로그에 `빈 응답 HTTP 302 Location=...`이 남습니다 (인증키는 `<KEY>`로 가림). 복구 후 `--restart`로 이어서 실행하면 됩니다.
- **병원 상세 누락:** 가격이 있는 병원 중 약 38%는 HIRA 세부정보 API가 0건을 반환해 `hospital_detail.detail`이 비어 있습니다 (HIRA 원본에 없는 데이터).
- **기준 월:** 가격 이력의 `snapshot_month`는 `requestedAt`이 속한 달의 1일입니다. 같은 달에 다시 실행하면 그 달 기록을 지우고 새로 채웁니다.
