export interface SessionSync {
  publish(): void;
  subscribe(listener: () => void): () => void;
  close(): void;
}

/** Signal only; never a user, CSRF, token, password, OTP or request payload. */
export function browserSessionSync(): SessionSync | undefined {
  if (typeof window === "undefined" || typeof BroadcastChannel === "undefined")
    return;
  const channel = new BroadcastChannel("digitalbanking-session-v2");
  const listeners = new Set<() => void>();
  channel.onmessage = ({ data }: MessageEvent<unknown>) => {
    if (data === "invalidate") for (const listener of listeners) listener();
  };
  return {
    publish: () => channel.postMessage("invalidate"),
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    close() {
      listeners.clear();
      channel.close();
    },
  };
}
