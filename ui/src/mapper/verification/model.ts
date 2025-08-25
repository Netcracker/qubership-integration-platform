export interface VerificationError {
  message: string;
}

export interface MessageContext<T> {
  entity: T;
  errors: VerificationError[];
}

export type MessageSupplier<T> = (context: MessageContext<T>) => string;

export abstract class Verifier<T> {
  public abstract verify(entity: T): VerificationError[];

  public and(verifier: Verifier<T>): Verifier<T> {
    return new (class extends Verifier<T> {
      constructor(private parent: Verifier<T>) {
        super();
      }

      verify(entity: T): VerificationError[] {
        return (
          [this.parent, verifier]
            .map((v) => v.verify(entity))
            .find((errors) => errors.length > 0) ?? []
        );
      }
    })(this);
  }

  public or(verifier: Verifier<T>): Verifier<T> {
    return new (class extends Verifier<T> {
      constructor(private parent: Verifier<T>) {
        super();
      }

      verify(entity: T): VerificationError[] {
        const results = [this.parent, verifier].map((v) => v.verify(entity));
        const anySucceeded = results.some((r) => r.length === 0);
        return anySucceeded ? [] : results[0];
      }
    })(this);
  }

  public withMessage(messageOrSupplier: string | MessageSupplier<T>) {
    const supplier: MessageSupplier<T> =
      typeof messageOrSupplier === "string"
        ? () => messageOrSupplier
        : messageOrSupplier;

    return new (class extends Verifier<T> {
      constructor(private verifier: Verifier<T>) {
        super();
      }

      verify(entity: T): VerificationError[] {
        const errors = this.verifier.verify(entity);
        return errors.length ? [{ message: supplier({ entity, errors }) }] : [];
      }
    })(this);
  }
}

class LessThan<T> extends Verifier<T> {
  constructor(private limit: T) {
    super();
  }

  verify(entity: T): VerificationError[] {
    return entity >= this.limit
      ? [{ message: `must be less than ${this.limit?.toString()}` }]
      : [];
  }
}

class GreaterThan<T> extends Verifier<T> {
  constructor(private limit: T) {
    super();
  }

  verify(entity: T): VerificationError[] {
    return entity <= this.limit
      ? [{ message: `must be greater than ${this.limit?.toString()}` }]
      : [];
  }
}

class EqualTo<T> extends Verifier<T> {
  constructor(private value: T) {
    super();
  }

  verify(entity: T): VerificationError[] {
    return entity === this.value
      ? []
      : [{ message: `must be equal to ${this.value?.toString()}` }];
  }
}

class AlwaysPass<T> extends Verifier<T> {
  constructor() {
    super();
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  verify(_entity: T): VerificationError[] {
    return [];
  }
}

class AlwaysFail<T> extends Verifier<T> {
  constructor() {
    super();
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  verify(_entity: T): VerificationError[] {
    return [{ message: "fail" }];
  }
}

class LogicalNegation<T> extends Verifier<T> {
  constructor(private verifier: Verifier<T>) {
    super();
  }

  verify(entity: T): VerificationError[] {
    return this.verifier.verify(entity).length
      ? []
      : [{ message: "must be logical negation of nested verification result" }];
  }
}

class MappingVerifier<T0, T1> extends Verifier<T0> {
  constructor(
    private mapFn: (e: T0) => T1,
    private verifier: Verifier<T1>,
  ) {
    super();
  }

  verify(entity: T0): VerificationError[] {
    return this.verifier.verify(this.mapFn(entity));
  }
}

class FilterVerifier<T> extends Verifier<T[]> {
  constructor(
    private predicate: (value: T, index: number, array: T[]) => boolean,
    private verifier: Verifier<T[]>,
  ) {
    super();
  }

  verify(entity: T[]): VerificationError[] {
    return this.verifier.verify(
      entity.filter((value, index, array) =>
        this.predicate(value, index, array),
      ),
    );
  }
}

class ForEachVerifier<T> extends Verifier<T[]> {
  constructor(private verifier: Verifier<T>) {
    super();
  }

  verify(entity: T[]): VerificationError[] {
    return entity
      .map((e) => this.verifier.verify(e))
      .reduce((e0, e1) => [...e0, ...e1], []);
  }
}

class AnyMatchVerifier<T> extends Verifier<T[]> {
  constructor(private verifier: Verifier<T>) {
    super();
  }

  verify(entity: T[]): VerificationError[] {
    return entity
      .map((e) => this.verifier.verify(e))
      .reduce((e0, e1) => (e0?.length === 0 ? [] : e1?.length === 0 ? [] : e0));
  }
}

export interface SwitchBranch<T> {
  case: Verifier<T>;
  verify: Verifier<T>;
}

class SwitchVerifier<T> extends Verifier<T> {
  constructor(
    private branches: SwitchBranch<T>[],
    private defaultVerifier: Verifier<T> = null,
  ) {
    super();
  }

  verify(entity: T): VerificationError[] {
    const verifier =
      this.branches.find((branch) => branch.case.verify(entity).length === 0)
        ?.verify ?? this.defaultVerifier;
    return verifier?.verify(entity) || [];
  }
}

export function pass<T>(): Verifier<T> {
  return new AlwaysPass();
}

export function fail<T>(): Verifier<T> {
  return new AlwaysFail();
}

export function anyOf<T>(verifiers: Verifier<T>[]): Verifier<T> {
  return verifiers.length > 0
    ? verifiers.reduce((v0, v1) => v0.or(v1))
    : pass();
}

export function allOf<T>(verifiers: Verifier<T>[]): Verifier<T> {
  return verifiers.length > 0
    ? verifiers.reduce((v0, v1) => v0.and(v1))
    : pass();
}

export function lessThan<T>(limit: T): Verifier<T> {
  return new LessThan(limit);
}

export function greaterThan<T>(limit: T): Verifier<T> {
  return new GreaterThan(limit);
}

export function equalTo<T>(value: T): Verifier<T> {
  return new EqualTo(value);
}

export function not<T>(verifier: Verifier<T>): Verifier<T> {
  return new LogicalNegation(verifier);
}

export function among<T>(values: T[]): Verifier<T> {
  return anyOf(values.map((value) => equalTo(value)));
}

export function map<T0, T1>(
  mapFn: (e: T0) => T1,
  verifier: Verifier<T1>,
): Verifier<T0> {
  return new MappingVerifier(mapFn, verifier);
}

export function filter<T>(
  predicate: (value: T, index: number, array: T[]) => boolean,
  verifier: Verifier<T[]>,
): Verifier<T[]> {
  return new FilterVerifier(predicate, verifier);
}

export function forEach<T>(verifier: Verifier<T>): Verifier<T[]> {
  return new ForEachVerifier(verifier);
}

export function anyMatch<T>(verifier: Verifier<T>): Verifier<T[]> {
  return new AnyMatchVerifier(verifier);
}

export function count<T>(verifier: Verifier<number>): Verifier<T[]> {
  return map((entity) => entity.length, verifier);
}

export function exists<T>(): Verifier<T> {
  return not(equalTo<T>(null).or(equalTo<T>(undefined)));
}

export function switchOf<T>(
  branches: SwitchBranch<T>[],
  defaultVerifier: Verifier<T> = null,
): Verifier<T> {
  return new SwitchVerifier(branches, defaultVerifier ?? pass());
}
