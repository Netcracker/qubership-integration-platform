/** Align keys with `tests/__mocks__/domainsTablesLayoutModule.cjs` (Jest `moduleNameMapper`). */
export interface DomainsTablesLayoutTestClasses {
  pageRoot: string;
  tableSection: string;
  mainTable: string;
  nestedExpandWrap: string;
  nestedTableHost: string;
  nestedTable: string;
}

export const mockDomainsTablesLayoutClasses: DomainsTablesLayoutTestClasses = {
  pageRoot: "DomainsTablesLayout-pageRoot-test",
  tableSection: "DomainsTablesLayout-tableSection-test",
  mainTable: "DomainsTablesLayout-mainTable-test",
  nestedExpandWrap: "DomainsTablesLayout-nestedExpandWrap-test",
  nestedTableHost: "DomainsTablesLayout-nestedTableHost-test",
  nestedTable: "DomainsTablesLayout-nestedTable-test",
};

export interface CommonStyleContainerTestModule {
  __esModule: true;
  default: {
    container: string;
  };
}

export const mockCommonStyleContainerModule: CommonStyleContainerTestModule = {
  __esModule: true,
  default: {
    container: "CommonStyle-container-test",
  },
};

export const mockDomainsTablesLayoutModule: {
  __esModule: true;
  default: DomainsTablesLayoutTestClasses;
} = {
  __esModule: true,
  default: mockDomainsTablesLayoutClasses,
};
