import { useEffect, useState } from "react";
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
import { canOpenCustomerDashboard } from "../../../shared/auth/feature-policy";
import { createV1CustomerReadApi } from "../../accounts/v1/customer-read-api";
import { V1CustomerDashboard } from "../../accounts/v1/V1CustomerDashboard";

const v1Path = (path: string) => path;

/** Real v1 auth entry. No MSW, fixture state, bearer or refresh endpoint. */
export function AuthApiApp() {
  const [{ controller, reader }] = useState(() => {
    const baseUrl = import.meta.env.VITE_API_BASE_URL || "/api/v1";
    const reader = createV1CustomerReadApi({
      baseUrl,
      onSessionExpired: () => controller.restartLogin(),
    });
    const controller = createV1Session({
      baseUrl,
      sync: browserSessionSync(),
      onSessionCleared() {
        reader.invalidate();
        void queryClient.cancelQueries();
        queryClient.clear();
        transferFlow.getState().clear();
        operatorFlow.getState().setSeed(null);
        legacySession.getState().clear();
      },
    });
    return { controller, reader };
  });
  useEffect(
    () => () => {
      controller.dispose();
      reader.dispose();
    },
    [controller, reader],
  );
  return (
    <BrowserRouter>
      <V2AuthFlow
        session={controller}
        customerDashboard
        pathFor={v1Path}
        renderFrame={(content) => <AuthSplitLayout>{content}</AuthSplitLayout>}
        renderAuthenticated={(auth, logout, ending) =>
          canOpenCustomerDashboard(auth.user) ? (
            <V1CustomerDashboard
              auth={auth}
              reader={reader}
              logout={logout}
              ending={ending}
            />
          ) : (
            <V2SessionSummary auth={auth} logout={logout} ending={ending} />
          )
        }
      />
    </BrowserRouter>
  );
}
