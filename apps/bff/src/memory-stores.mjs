export function createMemoryStores() {
  const loginAttempts = new Map();
  const sessions = new Map();
  const contexts = new Map();

  return {
    loginAttempts: {
      async put(stateHash, value) { loginAttempts.set(stateHash, structuredClone(value)); },
      async take(stateHash) {
        const value = loginAttempts.get(stateHash);
        loginAttempts.delete(stateHash);
        return value ? structuredClone(value) : null;
      },
    },
    sessions: {
      async put(sessionHash, value) { sessions.set(sessionHash, structuredClone(value)); },
      async get(sessionHash) {
        const value = sessions.get(sessionHash);
        return value ? structuredClone(value) : null;
      },
      async touch(sessionHash, lastSeenAt) {
        const value = sessions.get(sessionHash);
        if (value) sessions.set(sessionHash, { ...value, lastSeenAt });
      },
      async delete(sessionHash) { sessions.delete(sessionHash); },
    },
    contexts: {
      async put(contextHash, value) { contexts.set(contextHash, structuredClone(value)); },
      async get(contextHash) {
        const value = contexts.get(contextHash);
        return value ? structuredClone(value) : null;
      },
      async delete(contextHash) { contexts.delete(contextHash); },
      async deleteForSession(sessionHash) {
        for (const [key, value] of contexts) {
          if (value.sessionHash === sessionHash) contexts.delete(key);
        }
      },
    },
  };
}
