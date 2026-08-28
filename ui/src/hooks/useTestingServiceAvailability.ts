import { useQuery } from "@tanstack/react-query";
import { api } from "../api/api";
import { TestingServiceMode } from "../api/apiTypes";
import { isVsCode } from "../api/rest/vscodeExtensionApi";
import { getConfig } from "../appConfig";

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
 *
 * Two answers have to agree. The installation names its own mode, and a live one
 * settles the question here without a request: a testing service that was
 * deployed with the wrong mode cannot open the section on its own. Only where
 * the installation names no mode, or names a mode that is not production, is the
 * service asked.
 */
export function useTestingServiceAvailability(): TestingServiceAvailability {
  const productionInstallation = getConfig().productionMode === true;
  const { data, isLoading } = useQuery<TestingServiceMode>({
    queryKey: testingServiceModeQueryKey,
    queryFn: () => api.getTestingServiceMode(),
    enabled: !isVsCode && !productionInstallation,
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnMount: false,
  });

  if (productionInstallation) {
    return { isAvailable: false, isLoading: false };
  }
  // A disabled query holds no data and reports isLoading false, so the VS Code
  // case needs no guard of its own here.
  return { isAvailable: data?.production === false, isLoading };
}
