# Mini Redis 아키텍처 및 단계별 구현 가이드

이 문서는 Mini Redis 프로젝트를 처음부터 단계별로 구현하면서 어떤 기술을 활용했고 왜 그렇게 설계했는지 기록한 기술 문서입니다.

---

## 1. Gradle Kotlin DSL 멀티 모듈 구조 설계 (Phase 1)

### 무엇을 구현했는가
하나의 루트 프로젝트(`mini-redis`) 아래에 직접 만드는 서버 모듈(`redis-server`)과 이를 사용하는 스프링 클라이언트 모듈(`spring-app`)을 분리하고 TCP 6379 포트 통신 기반을 구축했습니다.

### 어떻게 구현했는가
* 루트 `build.gradle.kts`의 `subprojects` 블록을 활용해 두 모듈이 **JDK 21 (`jvmToolchain(21)`)**과 **Kotlin 2.1.0**, **JUnit 5 (`useJUnitPlatform()`)** 설정을 공통으로 상속받도록 구성했습니다.
* `spring-app` 모듈의 운영 코드(`implementation`)에는 `redis-server` 모듈 의존성을 넣지 않았습니다. 두 모듈이 클래스나 메모리를 직접 공유하지 않고 오직 **TCP 소켓(`localhost:6379`)과 RESP 프로토콜**로만 통신하도록 격리해 실제 운영 환경의 Redis 연동과 동일한 조건을 만들었습니다.
* 테스트 코드 실행 시에만 `testImplementation(project(":redis-server"))`를 통해 내장 서버를 자동으로 띄우고 통합 테스트를 수행합니다.

---

## 2. RESP2(REdis Serialization Protocol) 파서와 직렬화기 (Phase 2)

### 무엇을 구현했는가
Redis 클라이언트(`redis-cli`, Spring Lettuce)와 서버가 주고받는 텍스트 및 바이너리 혼합 규격인 **RESP2 프로토콜**의 데이터 모델(`RespValue`), 인코더(`RespEncoder`), 디코더(`RespDecoder`)를 구현했습니다.

### 어떻게 구현했는가
* **타입 안전한 프로토콜 모델링 (`RespValue.kt`)**
  * Kotlin의 `sealed interface`를 활용해 RESP의 5가지 데이터 타입인 `SimpleString`(`+`), `Error`(`-`), `Integer`(`:`), `BulkString`(`$`), `Array`(`*`)를 정의했습니다.
  * `when` 분기문에서 모든 하위 타입을 강제로 처리하게 만들어 누락되는 프로토콜 타입이 없도록 컴파일 타임에 보장했습니다.
* **UTF-8 바이트 길이 기반 직렬화 (`RespEncoder.kt`)**
  * Redis의 `BulkString`은 글자 수가 아니라 **UTF-8 바이트 길이**를 헤더(`$길이\r\n`)로 전송합니다.
  * 한글(`"마이"`는 2글자이지만 UTF-8에서 6바이트)을 스프링과 주고받을 때 데이터가 잘리지 않도록 `text.length` 대신 `text.toByteArray(Charsets.UTF_8).size`로 바이트 길이를 계산해 직렬화했습니다.
* **바이트 스트림 직접 파싱과 인라인 명령어 지원 (`RespDecoder.kt`)**
  * `BufferedReader.readLine()`을 쓰면 `BulkString` 본문 내부에 포함된 개행 문자(`\r\n`)나 멀티바이트 문자를 정확한 바이트 수만큼 끊어 읽을 수 없습니다.
  * 이를 해결하기 위해 `InputStream`에서 직접 바이트를 읽는 커스텀 `readLine()`과 정확한 바이트 배열(`ByteArray(byteLength)`)을 채우는 `decodeBulkString()`을 작성했습니다.
  * 첫 글자가 RESP 접두사(`+`, `-`, `:`, `$`, `*`)로 시작하면 표준 RESP 파싱을 수행하고, 일반 알파벳으로 시작하면 `decodeInlineCommand()`로 분기해 개발자가 `nc localhost 6379`에서 `SET key value`처럼 평문으로 입력해도 배열 명령어로 자동 변환되게 만들었습니다.

---

## 3. 커맨드 디스패처와 스프링 Lettuce 핸드셰이크 연동 (Phase 3 ~ Phase 4)

### 무엇을 구현했는가
네트워크 계층과 명령어 실행 계층을 분리하고, Spring Boot의 기본 Redis 드라이버인 **Lettuce**가 우리 서버를 실제 Redis 서버로 인식해 `StringRedisTemplate`으로 통신할 수 있게 만들었습니다.

### 어떻게 구현했는가
* **관심사 분리 (`CommandDispatcher.kt`)**
  * `MiniRedisServer`는 소켓 채널 입출력만 담당하고, 파싱된 `RespValue`를 해석해 `RedisDatabase`를 호출하는 역할은 모두 `CommandDispatcher`에 위임했습니다.
* **Lettuce 초기 핸드셰이크 프로토콜 협상**
  * Spring Boot 3.x에 포함된 Lettuce 6 클라이언트는 TCP 연결 직후 `HELLO 3` 명령어를 보내 RESP3 지원 여부를 질의합니다.
  * 이에 대해 Redis 표준 폴백 에러인 `-NOPROTO unsupported protocol version`을 반환하도록 구현하여, Lettuce가 연결을 끊지 않고 자동으로 우리가 구현한 **RESP2 프로토콜 모드**로 전환하도록 유도했습니다.
  * 이어지는 `CLIENT SETINFO`, `SELECT`, `INFO` 요청에 정상 응답을 내려주어 `StringRedisTemplate`과 완벽히 호환되게 만들었습니다.
* **동적 포트 주입 통합 테스트 (`SpringRedisIntegrationTest.kt`)**
  * 서버를 `port = 0`으로 기동해 OS로부터 충돌 없는 빈 포트를 할당받고, Spring Test의 `@DynamicPropertySource`를 활용해 실제 바인딩된 포트(`miniRedisServer.boundPort`)를 `spring.data.redis.port` 프로퍼티에 실시간 주입했습니다.

---

## 4. Java NIO `Selector` 기반 싱글 스레드 이벤트 루프 (Phase 5)

### 무엇을 구현했는가
초기의 멀티스레드 블로킹 소켓(`ServerSocket` + 클라이언트당 가상 스레드) 구조를 걷어내고, 실제 Redis의 핵심 아키텍처인 **I/O Multiplexing 기반 싱글 스레드 이벤트 루프**와 원자적 증감 명령어(`INCR`, `DECR`, `INCRBY`, `DECRBY`)를 구현했습니다.

### 어떻게 구현했는가
* **논블로킹 채널과 `Selector` 멀티플렉싱 (`MiniRedisServer.kt`)**
  * `ServerSocketChannel`과 각 클라이언트의 `SocketChannel`을 모두 논블로킹 모드(`configureBlocking(false)`)로 설정하고 하나의 `Selector`에 등록했습니다.
  * 단일 이벤트 루프 스레드가 `selector.select(100)`를 호출해 연결 요청(`OP_ACCEPT`)이나 읽을 데이터가 도착한(`OP_READ`) 채널만 골라서 순차적으로 처리합니다.
* **패킷 파편화(Fragmentation) 및 파이프라이닝(Pipelining) 대응**
  * 논블로킹 소켓에서는 하나의 명령어가 여러 TCP 패킷으로 쪼개져 도착하거나, 반대로 여러 명령어가 한 번에 붙어서 도착할 수 있습니다.
  * 이를 처리하기 위해 각 클라이언트의 `SelectionKey`에 누적 버퍼(`ByteArrayOutputStream`)를 부착했습니다.
  * 데이터를 읽을 때마다 누적 버퍼에 추가한 뒤 `ByteArrayInputStream`의 `mark()`와 `reset()`을 활용해 완성된 명령어들은 반복문으로 모두 실행하고, 아직 덜 도착한 조각 데이터는 `reset()`으로 되돌려 다음 `OP_READ` 이벤트 때 이어서 파싱하도록 구현했습니다.
* **락(Lock) 없는 동시성 제어 (`RedisDatabase.kt`)**
  * 모든 명령어를 단 하나의 이벤트 루프 스레드가 순서대로 실행하므로, 두 스레드가 동시에 저장소를 읽고 쓰는 경쟁 상태(Race Condition)가 원천적으로 불가능합니다.
  * 덕분에 `RedisDatabase` 내부에서 무거운 `ConcurrentHashMap`이나 `synchronized` 블록을 모두 제거하고 일반 `HashMap`만 사용하면서도, 10개 클라이언트가 동시에 100번씩 `INCR`을 호출하는 테스트에서 유실 없이 정확히 `1000`을 보장합니다.

---

## 5. 이중 만료 전략을 통한 TTL 제어 (Phase 6)

### 무엇을 구현했는가
키마다 유효기간(TTL)을 부여하는 `EXPIRE`, `PEXPIRE`, `TTL`, `PTTL`, `PERSIST`, `SET ... EX/PX` 명령어와, 만료된 키를 메모리에서 제거하는 실제 Redis의 **소극적 만료(Lazy Expiration)** 및 **적극적 만료(Active Expiration)** 전략을 구현했습니다.

### 어떻게 구현했는가
* **가상 시계 주입을 통한 결정론적 테스트 (`clock: () -> Long`)**
  * `RedisDatabase` 생성자에 현재 시각을 반환하는 함수(`clock`)를 주입받도록 설계했습니다. 운영 환경에서는 `System.currentTimeMillis()`를 사용하고, 단위 테스트에서는 변수를 조작해 `Thread.sleep` 대기 없이 즉시 시간 경과에 따른 만료를 검증합니다.
* **소극적 만료 (`purgeIfExpired`)**
  * `get`, `exists`, `del`, `incrBy`, `ttl`, `hget` 등 특정 키에 접근하는 모든 메서드의 시작 부분에서 `expires` 맵의 만료 시각을 확인합니다. 만료 시각이 지났다면 즉시 `store`와 `expires`에서 해당 키를 삭제하고 처음부터 없었던 키로 처리합니다.
* **이벤트 루프 결합형 적극적 만료 (`activeExpireCycle`)**
  * 만료 시간을 설정한 뒤 아무도 다시 조회하지 않는 키가 메모리에 방치되는 것을 막기 위해 `MiniRedisServer`의 이벤트 루프(`runEventLoop`)가 `100ms`(초당 10회)마다 `database.activeExpireCycle()`을 호출합니다.
  * 한 번에 모든 키를 전수 검사하면 이벤트 루프가 멈추므로, 실제 Redis 알고리즘과 동일하게 만료 설정된 키 중 최대 20개만 샘플링하여 만료된 키를 지우고, 샘플 중 25%를 초과하는 키가 만료되었다면 즉시 다음 20개 샘플링을 반복합니다.
  * 별도의 청소 스레드를 띄우지 않고 기존 이벤트 루프 스레드가 청소를 수행하므로 `HashMap`에 대한 동시 접근 충돌이 발생하지 않습니다.

---

## 6. 단일 키 공간 합 타입 설계와 `Hash` 자료구조 (Phase 7-1)

### 무엇을 구현했는가
하나의 키 안에 여러 필드와 값을 저장하는 `Hash` 자료구조(`HSET`, `HGET`, `HGETALL`, `HDEL`)와 서로 다른 타입의 명령어를 호출했을 때의 `WRONGTYPE` 에러 방어 로직을 구현했습니다.

### 어떻게 구현했는가
* **`sealed interface RedisValue` 기반 단일 키 공간 통합**
  * 자료구조마다 별도의 맵(`stringStore`, `hashStore`)을 따로 두면 동일한 키가 두 맵에 동시에 존재하는 모순이 생길 수 있고, `DEL`이나 `EXPIRE`를 수행할 때마다 모든 맵을 검사해야 합니다.
  * 이를 방지하기 위해 저장소 값을 표현하는 합 타입 `sealed interface RedisValue`를 도입하고 하위 타입으로 `StringVal(val value: String)`과 `HashVal(val fields: LinkedHashMap<String, String>)`을 정의한 뒤, 단일 `store = HashMap<String, RedisValue>()`에서 모든 키를 통합 관리하도록 재설계했습니다.
  * 이 구조 덕분에 기존의 `del`, `exists`, `expire`, `ttl`, `purgeIfExpired`, `activeExpireCycle`은 단 한 줄의 수정 없이 `Hash` 타입 키에도 그대로 작동합니다.
* **경계에서의 타입 에러 변환 (`RedisWrongTypeException`)**
  * `StringVal`이 저장된 키에 `hset`이나 `hget`을 호출하거나, `HashVal`이 저장된 키에 `get`이나 `incrBy`를 호출하면 저장소가 `RedisWrongTypeException`을 던지고, `CommandDispatcher.dispatch()`가 이를 잡아 Redis 표준 에러인 `-WRONGTYPE Operation against a key holding the wrong kind of value`로 응답합니다.
* **빈 컬렉션 자동 정리**
  * `HDEL`로 해시 내부의 마지막 필드가 삭제되어 `fields.isEmpty()`가 되면, 빈 해시 객체를 남겨두지 않고 `store`와 `expires`에서 키 자체를 즉시 제거해 메모리 누수를 막았습니다.
