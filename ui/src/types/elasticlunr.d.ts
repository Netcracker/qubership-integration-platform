declare module "lunr" {
  const lunr: unknown;
  export = lunr;
}

declare module "elasticlunr" {
  namespace elasticlunr {
    interface Index<T> {
      search(query: string, config?: unknown): unknown;

      documentStore: {
        getDoc(ref: string): T | undefined;
      };

      setRef(field: string): void;

      addField(field: string): void;

      saveDocument(save: boolean): void;

      addDoc(doc: T): void;
    }

    namespace Index {
      function load<T>(serializedIndex: unknown): Index<T>;
    }

    function stemmer(word: string): string;
  }

  function elasticlunr<T>(): elasticlunr.Index<T>;

  export = elasticlunr;
}
