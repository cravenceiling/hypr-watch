import { z } from 'zod';

export const noteId=z.string().refine(value=>value.length>0 && !/[^A-Za-z0-9_-]/.test(value),'note_id must contain only letters, digits, underscores, or hyphens.');

export function date(name: string, value: unknown): number {
  const parsed = typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? Date.parse(value + 'T00:00:00Z') : NaN;
  if (!Number.isFinite(parsed) || new Date(parsed).toISOString().slice(0, 10) !== value || String(value).startsWith('0000')) {
    throw new Error(`${name} must be a valid date in YYYY-MM-DD format.`);
  }
  return parsed / 86400000;
}
export const iso = (day: number): string => new Date(day * 86400000).toISOString().slice(0, 10);
export function dateRange(start: unknown, end: unknown): string {
  const first = date('start_date', start), last = date('end_date', end);
  if (first > last) throw new Error('start_date must be on or before end_date.');
  if (last - first + 1 > 400) throw new Error('Date range must not exceed 400 days.');
  return `from=${start}&to=${end}`;
}
export function bool(name: string, value: unknown): asserts value is boolean {
  if (typeof value !== 'boolean') throw new Error(`${name} must be true or false.`);
}
export function time(name: string, value: unknown): asserts value is string {
  if (typeof value !== 'string' || /^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.exec(value)?.[0] !== value) throw new Error(`${name} must be a time in HH:MM 24-hour format.`);
}
export const control = (value: string): boolean => /[\x00-\x1f\x7f-\x9f]/.test(value);
export function alarmId(value: unknown): string {
  if (typeof value !== 'string' || /^(?:0|[1-9][0-9]{0,9})$/.exec(value)?.[0] !== value) throw new Error('alarm_id must be 0 or 1 to 10 decimal digits without a leading zero.');
  return value;
}
export function faceId(value: unknown): string {
  if (typeof value !== 'string' || /^[0-9]{1,20}$/.exec(value)?.[0] !== value) throw new Error('face_id must be 1 to 20 decimal digits.');
  return value;
}
export function repeat(value: unknown): void {
  if (!Array.isArray(value)) throw new Error('repeat must be a list of days.');
  if (value.some(day => typeof day !== 'string' || !['mon','tue','wed','thu','fri','sat','sun'].includes(day))) throw new Error('repeat days must be one of: mon, tue, wed, thu, fri, sat, sun.');
}
export function label(value: unknown): void {
  if (typeof value !== 'string') throw new Error('label must be text.');
  if (value.trim().length > 40 || control(value.trim())) throw new Error('label must be at most 40 characters with no control characters.');
}
export function expect(value: unknown): void {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('expect must be an object of alarm fields.');
  for (const [key, field] of Object.entries(value)) {
    if (key === 'time') time('expect.time', field);
    else if (key === 'repeat') repeat(field);
    else if (key === 'repeat_mode') { if (typeof field !== 'string') throw new Error('expect.repeat_mode must be text.'); }
    else if (key === 'enabled' || key === 'smart') bool(`expect.${key}`, field);
    else throw new Error(`Unknown expect field ${key}.`);
  }
}
const boolSettings = new Set(['sleep_advanced_monitoring','sleep_breathing_quality','irregular_rhythm_alert','sleep_respiratory_rate','sedentary_reminder','sedentary_quiet','stress_auto','relax_reminder','goal_notifications','vitality_seven_day','vitality_daily_progress']);
const timeSettings = new Set(['sedentary_start','sedentary_end','sedentary_quiet_start','sedentary_quiet_end']);
const choices: Record<string, (string | number)[]> = {
  hr_interval: ['10min','1min','30min','off','smart'], hr_high_alert: [0,100,110,120,130,140,150], hr_low_alert: [0,40,45,50],
  spo2_mode: ['all_day','off','sleep'], spo2_low_alert: [0,80,85,90], secondary_goal: ['moving','standing'],
};
export function watchSettings(settings: unknown): void {
  if (!settings || typeof settings !== 'object' || Array.isArray(settings)) throw new Error('settings must be an object.');
  if (!Object.keys(settings).length) throw new Error('settings must contain at least one setting.');
  for (const [key, value] of Object.entries(settings)) {
    if (boolSettings.has(key)) bool(key, value);
    else if (timeSettings.has(key)) time(key, value);
    else if (Object.hasOwn(choices, key)) {
      if ((typeof value !== 'number' && typeof value !== 'string') || !choices[key].includes(value)) throw new Error(`${key} must be one of: ${choices[key].join(', ')}.`);
    } else throw new Error(`Unknown watch setting ${key}.`);
  }
}
export function nonNull(data: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(data).filter(([, value]) => value != null));
}
