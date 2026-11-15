import type {components} from './schema';
export declare function validateZoneView(data: unknown): data is components['schemas']['ZoneView'];
export declare function validateEventListResponse(data: unknown): data is components['schemas']['EventListResponse'];
export declare function validateEventDetail(data: unknown): data is components['schemas']['EventDetail'];
export declare function validateAuthUser(data: unknown): data is components['schemas']['AuthUser'];
export declare function validateCsrfResponse(data: unknown): data is components['schemas']['CsrfResponse'];
export declare function validateRestError(data: unknown): data is components['schemas']['RestError'];
