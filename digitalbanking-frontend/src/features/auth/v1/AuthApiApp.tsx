import { useState } from "react";
import { BrowserRouter } from "react-router-dom";
import { queryClient } from "../../../app/query";
import { transferFlow } from "../../../stores/transfer";
import { operatorFlow } from "../../../stores/operator";
import { session as legacySession } from "../../../stores/session";
import { createV1Session } from "../../../shared/auth/session";
import { browserSessionSync } from "../../../shared/auth/session-sync";
import { AuthSplitLayout } from "../v2/AuthSplitLayout";
import { V2AuthFlow } from "../v2/AuthFlow";
import { V2SessionSummary } from "../v2/AuthScreens";

const v1Path = (path: string) => path;

/** Real v1 auth entry. No MSW, fixture state, bearer or refresh endpoint. */
export function AuthApiApp() {
  const [controller] = useState(() =>
    createV1Session({
      baseUrl: import.meta.env.VITE_API_BASE_URL || "/api/v1",
      sync: browserSessionSync(),
      onSessionCleared() {
        void queryClient.cancelQueries();
        queryClient.clear();
        transferFlow.getState().clear();
        operatorFlow.getState().setSeed(null);
        legacySession.getState().clear();
      },
    }),
  );
  return (
    <BrowserRouter>
      <V2AuthFlow
        session={controller}
        pathFor={v1Path}
        renderFrame={(content) => <AuthSplitLayout>{content}</AuthSplitLayout>}
        renderAuthenticated={(auth, logout, ending) => (
          <V2SessionSummary auth={auth} logout={logout} ending={ending} />
        )}
      />
    </BrowserRouter>
  );
}
