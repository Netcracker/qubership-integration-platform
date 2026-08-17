import { useQuery } from "@tanstack/react-query";
import { api } from "../api/api";
import { TestingServiceMode } from "../api/apiTypes";
import { isVsCode } from "../api/rest/vscodeExtensionApi";

const testingServiceModeQueryKey = ["testing-service", "mode"];

export type TestingServiceAvailability = {
  /** True only when the service answered and is not running in production mode. */
  isAvailable: boolean;
  isLoading: boolean;
};

/**
 * Resolves once whether the testing section may be shown. An absent service is a
 * normal outcome, not an error: a failed request leaves the section hidden, and
 * retries are off so a missing deployment cannot produce a request storm.
 */
export function useTestingServiceAvailability(): TestingServiceAvailability {
  const { data, isLoading } = useQuery<TestingServiceMode>({
    queryKey: testingServiceModeQueryKey,
    queryFn: () => api.getTestingServiceMode(),
    enabled: !isVsCode,
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnMount: false,
  });

  // A disabled query holds no data and reports isLoading false, so the VS Code
  // case needs no guard of its own here.
  return { isAvailable: data?.production === false, isLoading };
}
