package com.arte.app.platform.persistence;

import com.arte.core.pojo.workspace.WorkspaceCandidateKey;

public interface WorkspaceCursorStore {

    record Cursor(String subjectId, WorkspaceCandidateKey key) {
    }

    String save(Cursor cursor);

    Cursor load(String token);
}
