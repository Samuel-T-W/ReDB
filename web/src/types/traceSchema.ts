// Mirrors trace.QueryTrace.CURRENT_SCHEMA_VERSION from the Java model (the
// source of truth). The TypeScript type generator emits interfaces and enums
// only, not `public static final` constants, so this one value is kept here by
// hand. It changes only when the trace wire format does.
export const CURRENT_SCHEMA_VERSION = 1;
