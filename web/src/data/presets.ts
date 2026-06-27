// Preset ranges the demo offers. With static traces the input surface is small
// and safe; these mirror the kind of title ranges run_query takes.
export interface RangePreset {
  id: string;
  label: string;
  start: string;
  end: string;
  hint: string;
}

export const RANGE_PRESETS: RangePreset[] = [
  { id: "narrow", label: "A — Af", start: "A", end: "Af", hint: "narrow range, few matches" },
  { id: "mid", label: "M — Mz", start: "M", end: "Mz", hint: "mid-size range" },
  { id: "wide", label: "S — Sz", start: "S", end: "Sz", hint: "wider range, more matches" },
  { id: "the", label: "The — Thf", start: "The", end: "Thf", hint: "the classic 'The ...' cluster" },
];

export const BUFFER_SIZES = [3, 5, 8, 12];
