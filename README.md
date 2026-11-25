# Mini Redis

Kotlin과 Java NIO `Selector` 기반의 싱글 스레드 이벤트 루프로 직접 구현한 인메모리 Redis 서버와 Spring Boot 연동 프로젝트입니다.

## 프로젝트 모듈 구조

* `redis-server` 모듈은 RESP2 프로토콜 파서, 싱글 스레드 이벤트 루프 네트워크 엔진, 인메모리 저장소, TTL 만료 처리기를 포함합니다.
* `spring-app` 모듈은 Spring Boot 3.4와 `StringRedisTemplate`을 사용해 `redis-server`의 6379 포트에 연결하는 클라이언트 애플리케이션입니다.
* 자세한 단계별 구현 원리와 기술 선택 배경은 [docs/implementation-guide.md](docs/implementation-guide.md)에 정리되어 있습니다.

## 서버 실행 및 접속 방법

1. `redis-server` 모듈의 `com.miniredis.server.MainKt`를 실행하면 `6379` 포트로 서버가 기동됩니다.
2. 터미널에서 `nc` 또는 `redis-cli`로 접속해 명령어를 전송할 수 있습니다:

```bash
nc localhost 6379
```

3. `spring-app` 모듈의 `com.miniredis.spring.SpringRedisApplicationKt`를 실행하면 `8080` 포트에서 스프링 부트 서버가 기동되며 `localhost:6379`의 Mini Redis 서버와 통신합니다.

---

## 지원 명령어 레퍼런스

새로운 명령어가 추가될 때마다 이 문서를 함께 갱신합니다.

### 1. 연결 및 서버 관리 명령어

#### `PING [message]`
서버와의 연결 상태를 확인합니다. 인자를 생략하면 `+PONG`을 반환하고, 메시지를 전달하면 해당 메시지를 `BulkString`으로 그대로 돌려줍니다.

```text
PING
+PONG

PING hello
$5
hello
```

#### `ECHO <message>`
전달한 문자열을 `BulkString` 형태로 그대로 반환합니다.

```text
ECHO mini-redis
$10
mini-redis
```

#### `DBSIZE`
현재 데이터베이스에 저장된 전체 키의 개수를 정수(`:N`)로 반환합니다.

```text
DBSIZE
:3
```

#### `FLUSHDB` / `FLUSHALL`
저장소의 모든 키와 만료 시간(TTL) 정보를 초기화하고 `+OK`를 반환합니다.

```text
FLUSHDB
+OK
```

#### 클라이언트 핸드셰이크 명령어 (`HELLO`, `CLIENT`, `SELECT`, `INFO`, `COMMAND`)
Spring Boot의 Lettuce 클라이언트나 `redis-cli`가 초기 연결을 맺을 때 자동으로 호출하는 명령어들입니다.

| 명령어 | 사용 형식 | 동작 및 반환값 |
| :--- | :--- | :--- |
| `HELLO` | `HELLO [protover]` | `-NOPROTO unsupported protocol version` 에러를 반환하여 클라이언트가 RESP2 모드로 폴백하도록 유도합니다. |
| `CLIENT` | `CLIENT <subcommand> [args...]` | 클라이언트의 라이브러리 메타데이터 등록 요청에 `+OK`를 반환합니다. |
| `SELECT` | `SELECT <index>` | 데이터베이스 인덱스 선택 요청에 `+OK`를 반환합니다. |
| `INFO` | `INFO [section]` | 서버 버전 정보(`redis_version:7.0.0-mini-redis`)를 담은 `BulkString`을 반환합니다. |
| `COMMAND` | `COMMAND [DOCS]` | 빈 배열(`*0`)을 반환하여 `redis-cli` 초기 접속 시 경고를 방지합니다. |

---

### 2. String 및 기본 Key 명령어

#### `SET <key> <value> [EX <seconds> | PX <milliseconds>]`
지정한 키에 문자열 값을 저장하고 `+OK`를 반환합니다. 이미 존재하는 키라면 기존 데이터 타입이나 기존 TTL 설정을 덮어씁니다. `EX` 옵션은 초 단위, `PX` 옵션은 밀리초 단위의 유효기간을 저장과 동시에 설정합니다.

```text
SET user:name 마이
+OK

SET session:token abc123xyz EX 60
+OK

SET temp:lock locked PX 500
+OK
```

#### `SETEX <key> <seconds> <value>` / `PSETEX <key> <milliseconds> <value>`
키에 문자열 값을 저장하면서 초 단위(`SETEX`) 또는 밀리초 단위(`PSETEX`) 만료 시간을 함께 설정합니다.

```text
SETEX otp:code 180 998877
+OK

PSETEX flash:key 1500 hello
+OK
```

#### `GET <key>`
지정한 키에 저장된 문자열 값을 `BulkString`으로 반환합니다. 키가 존재하지 않거나 만료되었으면 `$-1`(Null)을 반환합니다. 해당 키에 문자열이 아닌 다른 자료구조(`Hash` 등)가 저장되어 있으면 `-WRONGTYPE` 에러를 반환합니다.

```text
GET user:name
$6
마이

GET unknown:key
$-1
```

#### `DEL <key> [key ...]`
하나 이상의 키를 삭제하고, 실제로 존재해서 삭제된 키의 개수를 정수(`:N`)로 반환합니다.

```text
DEL user:name unknown:key
:1
```

#### `EXISTS <key> [key ...]`
전달한 키 목록 중 현재 존재하는 키의 개수를 정수(`:N`)로 반환합니다.

```text
EXISTS session:token unknown:key
:1
```

---

### 3. 원자적 숫자 증감 명령어

#### `INCR <key>` / `DECR <key>`
지정한 키의 64비트 정수 값을 `1` 증가(`INCR`)시키거나 `1` 감소(`DECR`)시키고 변경된 값을 정수(`:N`)로 반환합니다. 키가 존재하지 않으면 `0`에서 시작합니다. 저장된 값이 정수로 변환할 수 없는 문자열이거나 다른 자료구조이면 에러를 반환합니다.

```text
INCR article:1:views
:1

INCR article:1:views
:2

DECR article:1:views
:1
```

#### `INCRBY <key> <increment>` / `DECRBY <key> <decrement>`
지정한 키의 정수 값을 전달한 숫자만큼 증가(`INCRBY`)시키거나 감소(`DECRBY`)시키고 결과 값을 정수(`:N`)로 반환합니다.

```text
INCRBY article:1:views 10
:11

DECRBY article:1:views 5
:6
```

---

### 4. 유효기간(TTL) 및 만료 제어 명령어

#### `EXPIRE <key> <seconds>` / `PEXPIRE <key> <milliseconds>`
존재하는 키에 초 단위(`EXPIRE`) 또는 밀리초 단위(`PEXPIRE`) 만료 시간을 설정합니다. 설정에 성공하면 `:1`, 키가 존재하지 않으면 `:0`을 반환합니다.

```text
EXPIRE article:1:views 30
:1

PEXPIRE article:1:views 15000
:1
```

#### `TTL <key>` / `PTTL <key>`
키의 남은 유효기간을 초 단위(`TTL`) 또는 밀리초 단위(`PTTL`) 정수로 반환합니다.
* 키가 존재하고 만료 시간이 설정된 경우 남은 시간(`:N`)을 반환합니다.
* 키는 존재하지만 만료 시간이 설정되지 않은 영구 키인 경우 `:-1`을 반환합니다.
* 키가 존재하지 않거나 이미 만료된 경우 `:-2`를 반환합니다.

```text
TTL article:1:views
:15

TTL permanent:key
:-1

TTL unknown:key
:-2
```

#### `PERSIST <key>`
키에 설정된 만료 시간을 제거하여 만료되지 않는 영구 키로 전환합니다. 만료 시간이 실제로 제거되었으면 `:1`, 키가 없거나 원래 만료 시간이 없었으면 `:0`을 반환합니다.

```text
PERSIST article:1:views
:1
```

---

### 5. Hash 자료구조 명령어

#### `HSET <key> <field> <value> [field value ...]`
지정한 Hash 키에 하나 이상의 `field`와 `value` 쌍을 저장합니다. 기존에 없던 새로 추가된 필드의 개수를 정수(`:N`)로 반환합니다(이미 있던 필드의 값을 수정한 경우는 개수에서 제외됩니다).

```text
HSET user:10 name 마이 city 도쿄
:2

HSET user:10 city 서울 role ADMIN
:1
```

#### `HGET <key> <field>`
지정한 Hash 키에서 특정 `field`의 값을 `BulkString`으로 조회합니다. 키나 필드가 존재하지 않으면 `$-1`(Null)을 반환합니다.

```text
HGET user:10 name
$6
마이

HGET user:10 email
$-1
```

#### `HGETALL <key>`
지정한 Hash 키에 속한 모든 필드와 값을 `[field1, value1, field2, value2, ...]` 순서의 RESP 배열(`*N`)로 반환합니다. 키가 존재하지 않으면 빈 배열(`*0`)을 반환합니다.

```text
HGETALL user:10
*6
$4
name
$6
마이
$4
city
$6
서울
$4
role
$5
ADMIN
```

#### `HDEL <key> <field> [field ...]`
지정한 Hash 키에서 하나 이상의 필드를 삭제하고 실제로 삭제된 필드의 개수를 정수(`:N`)로 반환합니다. 모든 필드가 삭제되어 빈 Hash가 되면 해당 키 자체도 메모리에서 자동으로 제거됩니다.

```text
HDEL user:10 city role
:2
```
