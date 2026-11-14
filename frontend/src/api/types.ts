import type { components } from './generated/schema';
export type ZoneView = components['schemas']['ZoneView'];
export type EventList = components['schemas']['EventListResponse'];
export type EventDetail = components['schemas']['EventDetail'];
export type EventRecord = components['schemas']['EventRecord'];
export type AuthUser = components['schemas']['AuthUser'];
export type RestError = components['schemas']['RestError'];
export type IncidentState = EventRecord['state'];
