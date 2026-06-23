-- collab-docs 초기 스키마 (V1).
-- H2(MODE=PostgreSQL) 와 PostgreSQL 양쪽에서 동일하게 적용되도록 작성한다:
--  - 공통 타입만 사용(VARCHAR/TEXT/INTEGER/BOOLEAN/TIMESTAMP).
--  - PG 전용 다중컬럼 DDL/구문 회피, 제약/인덱스는 개별 문장으로 분리.

-- 문서 ------------------------------------------------------------------------
CREATE TABLE documents (
    id        VARCHAR(64)  NOT NULL,
    owner_id  VARCHAR(64)  NOT NULL,
    title     VARCHAR(512) NOT NULL,
    content   TEXT         NOT NULL,
    version   INTEGER      NOT NULL,
    folder_id VARCHAR(64)
);
ALTER TABLE documents ADD CONSTRAINT pk_documents PRIMARY KEY (id);
CREATE INDEX idx_documents_owner ON documents (owner_id);

-- 편집 로그(append-only). op_json 에 TextOperation 직렬화 저장. ----------------
CREATE TABLE edit_log (
    id                VARCHAR(64) NOT NULL,
    document_id       VARCHAR(64) NOT NULL,
    author_id         VARCHAR(64) NOT NULL,
    op_json           TEXT        NOT NULL,
    committed_version INTEGER     NOT NULL,
    committed_at      TIMESTAMP   NOT NULL
);
ALTER TABLE edit_log ADD CONSTRAINT pk_edit_log PRIMARY KEY (id);
CREATE INDEX idx_edit_log_doc_ver ON edit_log (document_id, committed_version);

-- 공유 ACL 헤더 --------------------------------------------------------------
CREATE TABLE share_acl (
    document_id VARCHAR(64) NOT NULL,
    owner_id    VARCHAR(64) NOT NULL
);
ALTER TABLE share_acl ADD CONSTRAINT pk_share_acl PRIMARY KEY (document_id);

-- 공유 ACL 엔트리(사용자별 권한) ----------------------------------------------
CREATE TABLE share_acl_entry (
    id          VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    role        VARCHAR(16) NOT NULL
);
ALTER TABLE share_acl_entry ADD CONSTRAINT pk_share_acl_entry PRIMARY KEY (id);
CREATE INDEX idx_acl_entry_doc ON share_acl_entry (document_id);

-- 코멘트(anchor 평면화 저장) -------------------------------------------------
CREATE TABLE comments (
    id           VARCHAR(64) NOT NULL,
    document_id  VARCHAR(64) NOT NULL,
    author_id    VARCHAR(64) NOT NULL,
    anchor_kind  VARCHAR(16) NOT NULL,
    anchor_start INTEGER     NOT NULL,
    anchor_end   INTEGER     NOT NULL,
    body         TEXT        NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    resolved     BOOLEAN     NOT NULL
);
ALTER TABLE comments ADD CONSTRAINT pk_comments PRIMARY KEY (id);
CREATE INDEX idx_comments_doc ON comments (document_id);
