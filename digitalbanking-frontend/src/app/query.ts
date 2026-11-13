import { QueryClient } from "@tanstack/react-query";
import { session } from "../stores/session";
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, staleTime: 15_000, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
});
session.subscribe((next, prev) => {
  if (next.generation !== prev.generation) {
    void queryClient.cancelQueries();
    queryClient.clear();
  }
});
export function refreshCustomerData() {
  void queryClient.invalidateQueries({
    queryKey: [session.getState().user?.userId],
  });
}
