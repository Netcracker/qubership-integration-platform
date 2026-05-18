import { jest } from "@jest/globals";

export type FakeMonaco = {
  languages: {
    getLanguages: jest.Mock;
    register: jest.Mock;
    setLanguageConfiguration: jest.Mock;
    setMonarchTokensProvider: jest.Mock;
    registerCompletionItemProvider: jest.Mock;
  };
  editor: {
    tokenize: jest.Mock;
    setModelMarkers: jest.Mock;
  };
};

export function createFakeMonaco(initial: { id: string }[] = []): FakeMonaco {
  const registered = [...initial];
  const register = jest.fn();
  register.mockImplementation((lang: unknown) => {
    registered.push(lang as { id: string });
  });
  return {
    languages: {
      getLanguages: jest.fn(() => registered),
      register,
      setLanguageConfiguration: jest.fn(),
      setMonarchTokensProvider: jest.fn(),
      registerCompletionItemProvider: jest.fn(() => ({ dispose: jest.fn() })),
    },
    editor: {
      tokenize: jest.fn(() => [[]]),
      setModelMarkers: jest.fn(),
    },
  };
}
