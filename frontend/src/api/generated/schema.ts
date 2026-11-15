export type JsonValue = (string | number | boolean | null) | JsonValue[] | {
            [key: string]: JsonValue;
        };
export interface paths {
    "/api/csrf": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/csrf
         * @description v1.4 역할: anonymous
         */
        get: operations["op01"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * POST /api/auth/login
         * @description v1.4 역할: anonymous+CSRF
         */
        post: operations["op02"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * POST /api/auth/logout
         * @description v1.4 역할: authenticated+CSRF
         */
        post: operations["op03"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/auth/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/auth/me
         * @description v1.4 역할: VIEWER|OPERATOR
         */
        get: operations["op04"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/zones/{id}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/zones/{id}/status
         * @description v1.4 역할: VIEWER|OPERATOR
         */
        get: operations["op05"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/events
         * @description v1.4 역할: VIEWER|OPERATOR
         */
        get: operations["op06"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/events/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/events/{id}
         * @description v1.4 역할: VIEWER|OPERATOR
         */
        get: operations["op07"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/zones/{id}/policy": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/zones/{id}/policy
         * @description v1.4 역할: VIEWER|OPERATOR
         */
        get: operations["op08"];
        /**
         * PUT /api/zones/{id}/policy
         * @description v1.4 역할: OPERATOR+CSRF
         */
        put: operations["op09"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/devices/{id}/commands": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * POST /api/devices/{id}/commands
         * @description v1.4 역할: OPERATOR+CSRF
         */
        post: operations["op10"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/commands/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/commands/{id}
         * @description v1.4 역할: VIEWER|OPERATOR VIEWER 및 만료 토큰은 result_data.confirmation_token=null. 유효 OPERATOR 응답만 문자열 허용. 공유 ZoneView에는 항상 null.
         */
        get: operations["op11"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/events/{id}/reviews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * POST /api/events/{id}/reviews
         * @description v1.4 역할: OPERATOR+CSRF
         */
        post: operations["op12"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/devices/{id}/images": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * POST /api/devices/{id}/images
         * @description v1.4 역할: scoped device credential
         */
        post: operations["op13"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/images/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/images/{id}
         * @description v1.4 역할: VIEWER|OPERATOR
         */
        get: operations["op14"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/stream": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * GET /api/stream
         * @description v1.4 역할: VIEWER|OPERATOR
         */
        get: operations["op15"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        Id: string;
        /** Format: date-time */
        Utc: string;
        /** @enum {string} */
        incident_state: "OBSERVING" | "STOP_CONFIRMED" | "GUIDING" | "GUIDED" | "LONG_UNRESOLVED" | "HELD" | "CLOSED_EXIT" | "CLOSED_MANUAL";
        /** @enum {string} */
        camera_health: "OK" | "SUSPECT" | "BLOCKED" | "NO_FRAME" | "RECONFIGURE";
        /** @enum {string} */
        mode: "AUTO" | "PAUSED" | "AWAITING_RESUME";
        /** @enum {string} */
        output_status: "IDLE" | "PLAYING" | "STOPPING" | "FAILED" | "UNKNOWN";
        /** @enum {string} */
        body_type: "CAR" | "BUS" | "TRUCK" | "UNKNOWN";
        /** @enum {string} */
        use_type: "TAXI" | "SCHOOL" | "DELIVERY" | "OTHER" | "UNKNOWN";
        /** @enum {string} */
        reason_codes: "FRAME_STALE" | "FRAME_GAP" | "INVALID_FRAME" | "OBSERVATION_MISSING" | "OCCLUSION_SUSPECT" | "IMAGE_DARK" | "IMAGE_BRIGHT" | "IMAGE_BLUR" | "BACKGROUND_UNRELIABLE" | "CAMERA_SHIFT" | "CALIBRATION_REQUIRED" | "IDENTITY_UNCERTAIN" | "MULTIPLE_TARGETS" | "ROI_BOUNDARY" | "VEHICLE_MOVING" | "OUTPUT_FAULT" | "CLOCK_UNTRUSTED";
        /** @enum {string} */
        command_type: "STOP_GUIDANCE" | "RESUME_GUIDANCE" | "REQUEST_SNAPSHOT" | "CLOSE_EVENT";
        /** @enum {string} */
        scope: "DEVICE" | "ZONE" | "EVENT";
        /** @enum {string} */
        command_result: "APPLIED" | "REJECTED" | "EXPIRED" | "FAILED";
        /** @enum {string} */
        server_command_status: "QUEUED" | "WAITING_RESULT" | "APPLIED" | "REJECTED" | "EXPIRED" | "FAILED" | "DELIVERY_TIMEOUT";
        /** @enum {string} */
        ack_status: "STORED" | "DUPLICATE" | "REJECTED";
        /** @enum {string} */
        config_status: "RECEIVED_PENDING" | "APPLIED" | "RECONFIGURE_REQUIRED" | "REJECTED";
        /** @enum {string} */
        pause_source: "USER" | "SCHEDULE" | "PHYSICAL" | "RESTART" | "RECONFIGURE" | "REARM_REQUIRED" | "OUTPUT_FAULT" | "OBSERVATION_REVIEW";
        /** @enum {string} */
        pause_status: "SCHEDULED" | "ACTIVE" | "ACK_REQUIRED" | "CLEARED";
        /** @enum {string} */
        connection_state: "ONLINE" | "STALE" | "OFFLINE";
        /** @enum {string} */
        role: "VIEWER" | "OPERATOR";
        /** @enum {string} */
        snapshot_purpose: "VIEW" | "RESUME" | "CLOSE_EVENT";
        /** @enum {string} */
        error_code: "INVALID_SCHEMA" | "UNSUPPORTED_SCHEMA" | "UNAUTHENTICATED" | "FORBIDDEN" | "NOT_FOUND" | "GONE" | "VERSION_CONFLICT" | "POLICY_CONTENT_CONFLICT" | "EVENT_MISMATCH" | "NO_ACTIVE_EVENT" | "MODE_CHANGED" | "INVALID_TOKEN" | "SNAPSHOT_EXPIRED" | "CLOCK_UNTRUSTED" | "PHYSICAL_STOP_ACTIVE" | "PAUSE_NOT_RELEASABLE" | "REARM_NOT_READY" | "OBSERVATION_UNSAFE" | "COMMAND_EXPIRED" | "OUTPUT_FAULT" | "MESSAGE_ID_CONFLICT" | "IMAGE_ID_CONFLICT" | "IDEMPOTENCY_CONFLICT" | "STORAGE_FULL";
        /** @enum {string} */
        event_type: "EVENT_STARTED" | "STOP_CONFIRMED" | "GUIDANCE_ATTEMPTED" | "GUIDANCE_PLAYBACK_COMPLETED" | "GUIDANCE_PLAYBACK_STOPPED" | "GUIDANCE_PLAYBACK_FAILED" | "OBSERVATION_HELD" | "OBSERVATION_RECOVERED" | "LONG_UNRESOLVED" | "EVENT_CLOSED_EXIT" | "EVENT_CLOSED_MANUAL" | "PAUSE_CHANGED" | "CONFIG_RECEIVED" | "CONFIG_ACTIVATED" | "DEVICE_RESTARTED" | "IMAGE_STATUS_CHANGED";
        /** @enum {string} */
        image_kind: "FIRST_GUIDANCE" | "EVENT_CLOSE" | "VIEW" | "RESUME_CONFIRM" | "CLOSE_CONFIRM";
        /** @enum {string} */
        image_status: "PENDING" | "AVAILABLE" | "FAILED" | "EXPIRED";
        /** @enum {string} */
        image_error_code: "QUEUE_FULL" | "IMAGE_STORAGE_FULL" | "NO_VALID_FRAME" | "ENCODE_FAILED" | "IMAGE_TOO_LARGE" | "HTTP_REJECTED" | "IMAGE_ID_CONFLICT" | "RETRY_EXHAUSTED" | "AGE_UNKNOWN" | "RETENTION_EXPIRED" | "RESTART_INCOMPLETE";
        /** @enum {string} */
        clock_state: "SYNCED" | "HOLDOVER" | "UNTRUSTED";
        DeviceEnvelope: {
            /**
             * @description const 2
             * @constant
             */
            schema_version: 2;
            /** @description id */
            device_id: string;
            /** @description id */
            zone_id: string;
            /** @description id; immutable across retry */
            message_id: string;
            /** @description id; source boot */
            boot_id: string;
            /** @description integer >=0; persisted new state-message sequence */
            device_seq: number;
            /** @description UTC|null */
            occurred_at: string | null;
            /** @description boolean */
            clock_synced: boolean;
            /** @description id|null */
            event_id: string | null;
        } & unknown;
        DeviceSnapshot: {
            /** @description id|null */
            current_event_id: string | null;
            /** @description incident_state|null */
            incident_state: components["schemas"]["incident_state"] | null;
            /** @description integer>=1|null */
            event_policy_version: number | null;
            /** @description integer 0..2; zero without event */
            guidance_count: number;
            /** @description integer>=0; zero without event */
            continuous_stop_ms: number;
            /** @description body_type|null */
            body_type: components["schemas"]["body_type"] | null;
            /** @description use_type|null */
            use_type: components["schemas"]["use_type"] | null;
            /** @description camera_health */
            camera_health: components["schemas"]["camera_health"];
            /** @description mode */
            mode: components["schemas"]["mode"];
            /** @description output_status */
            output_status: components["schemas"]["output_status"];
            /** @description array<reason_codes>; [] normal */
            reason_codes: components["schemas"]["reason_codes"][];
            /** @description array<Pause>; non-cleared only */
            pauses: components["schemas"]["ActivePause"][];
            /** @description integer>=1|null before first accepted policy */
            received_policy_version: number | null;
            /** @description integer>=1|null before first activated policy */
            active_policy_version: number | null;
            /** @description integer>=0 */
            mode_revision: number;
            /** @description boolean */
            rearm_required: boolean;
            /** @description integer>=0; equal envelope sequence */
            device_seq: number;
            /** @description id; equal envelope boot */
            boot_id: string;
            /** @description boolean; equals envelope; true iff clock_status.state is SYNCED or HOLDOVER */
            clock_synced: boolean;
            /** @description UTC|null */
            observed_at: string | null;
            /** @description boolean; false without current event */
            overdue: boolean;
            /** @description ClockStatus; clock_synced=true iff state is SYNCED or HOLDOVER */
            clock_status: components["schemas"]["ClockStatus"];
        } & (unknown & unknown);
        EventRecord: {
            /** @description id */
            event_id: string;
            /** @description incident_state */
            state: components["schemas"]["incident_state"];
            /** @description integer>=1 */
            event_policy_version: number;
            /** @description UTC|null */
            started_at: string | null;
            /** @description UTC|null */
            closed_at: string | null;
            /**
             * @description OBSERVED_EXIT|MANUAL|null
             * @enum {unknown}
             */
            close_reason: "OBSERVED_EXIT" | "MANUAL" | null;
            /** @description integer 0..2 */
            guidance_count: number;
            /** @description body_type */
            body_type: components["schemas"]["body_type"];
            /** @description use_type */
            use_type: components["schemas"]["use_type"];
            /** @description integer>=0 */
            continuous_stop_ms: number;
            /** @description boolean; true after unresolved threshold; retained independently of HELD */
            overdue: boolean;
        } & (unknown & unknown & unknown);
        Pause: {
            /** @description id */
            pause_id: string;
            /** @description pause_source */
            source: components["schemas"]["pause_source"];
            /** @description scope */
            scope: components["schemas"]["scope"];
            /** @description id|null; nonnull iff EVENT */
            target_event_id: string | null;
            /** @description UTC|null */
            starts_at: string | null;
            /** @description UTC|null */
            expires_at: string | null;
            /** @description pause_status */
            status: components["schemas"]["pause_status"];
            /** @description string 1..200 */
            reason: string;
        } & unknown;
        Timers: {
            /** @description integer>=1 default300000 */
            first_guidance_ms: number;
            /** @description integer>=1 default3000 */
            confirm_ms: number;
            /** @description integer>=1 default30000 */
            repeat_after_end_ms: number;
            /** @description integer1..2 default2 */
            max_attempts: number;
            /** @description integer>=1 default600000; must be > first_guidance_ms */
            unresolved_ms: number;
            /** @description integer>=1 default3000 */
            exit_confirm_ms: number;
        };
        Policy: {
            /** @description id */
            zone_id: string;
            /** @description integer>=1 server assigned */
            version: number;
            /** @description integer>=0 */
            base_version: number;
            /**
             * @description TIMING|GEOMETRY server computed
             * @enum {unknown}
             */
            change_class: "TIMING" | "GEOMETRY";
            /** @description Geometry */
            geometry: components["schemas"]["Geometry"];
            /** @description Timers */
            timers: components["schemas"]["Timers"];
            /** @description id */
            vision_profile_id: string;
            /** @description OperatingHours */
            operating_hours: components["schemas"]["OperatingHours"];
        };
        Command: {
            /**
             * @description const 2
             * @constant
             */
            schema_version: 2;
            /** @description id */
            command_id: string;
            /** @description id */
            device_id: string;
            /** @description command_type */
            type: components["schemas"]["command_type"];
            /** @description scope */
            scope: components["schemas"]["scope"];
            /** @description id|null */
            target_event_id: string | null;
            /**
             * Format: date-time
             * @description UTC; normally trusted server time, immediate STOP exception may carry best available server UTC; TTL exception determines freshness
             */
            issued_at: string;
            /**
             * Format: date-time
             * @description UTC exactly issued_at+60000ms
             */
            expires_at: string;
            /** @description integer>=0|null */
            expected_mode_revision: number | null;
            /** @description integer>=1|null */
            expected_received_policy_version: number | null;
            /** @description integer>=1|null */
            expected_active_policy_version: number | null;
            /** @description integer>=1|null */
            expected_event_policy_version: number | null;
            /** @description opaque string|null */
            confirmation_token: string | null;
            /** @description CommandParams selected by type */
            params: components["schemas"]["STOP_GUIDANCEParams"] | components["schemas"]["RESUME_GUIDANCEParams"] | components["schemas"]["REQUEST_SNAPSHOTParams"] | components["schemas"]["CLOSE_EVENTParams"];
        } & (unknown & unknown & unknown & unknown & unknown & unknown);
        CommandResultData: {
            /** @description array<id>; [] when irrelevant */
            pause_ids: string[];
            /** @description id|null */
            image_id: string | null;
            /** @description opaque string|null; operator only */
            confirmation_token: string | null;
            /** @description integer0..30000|null */
            token_valid_for_ms: number | null;
            /** @description id|null */
            closed_event_id: string | null;
        };
        ImageMetadata: {
            /** @description id */
            image_id: string;
            /** @description id|null */
            request_command_id: string | null;
            /** @description id|null */
            event_id: string | null;
            /** @description trusted UTC original frame capture time|null; never changed when bytes are reused */
            captured_at: string | null;
            /** @description boolean; trust of original captured_at, not upload time */
            clock_synced: boolean;
            /** @description id */
            boot_id: string;
            /** @description integer>=0 */
            mode_revision: number;
            /** @description integer>=1 */
            received_policy_version: number;
            /** @description integer>=1 */
            active_policy_version: number;
            /** @description integer>=1|null */
            event_policy_version: number | null;
            /** @description 64 lower hexadecimal chars */
            sha256: string;
            /** @description integer1..1280; max(width,height)<=1280; preserve aspect ratio, no upscaling */
            width: number;
            /** @description integer1..1280; max(width,height)<=1280; preserve aspect ratio, no upscaling */
            height: number;
            /** @description image_kind */
            image_kind: components["schemas"]["image_kind"];
            /**
             * @description const jpeg-1280-q80-v1; fixed output profile
             * @constant
             */
            image_profile_id: "jpeg-1280-q80-v1";
            /** @description trusted UTC job creation time|null; persisted for reboot age reconstruction */
            created_at: string | null;
            /** @description id|null; nonnull only when EVENT_CLOSE reuses an accepted CLOSE_CONFIRM JPEG */
            source_image_id: string | null;
        } & (unknown & unknown & unknown);
        ZoneView: {
            /** @description id */
            zone_id: string;
            /** @description integer>=0 persisted per-zone */
            view_revision: number;
            /** @description UTC|null; last state snapshot received */
            received_at: string | null;
            /** @description UTC|null */
            last_heartbeat_at: string | null;
            /** @description connection_state */
            connection_state: components["schemas"]["connection_state"];
            /** @description DeviceSnapshot|null before first report */
            snapshot: components["schemas"]["DeviceSnapshot"] | null;
            /** @description array<CommandView>; latest20 */
            commands: components["schemas"]["CommandView"][];
            /** @description array<ImageRef>; latest10 */
            image_refs: components["schemas"]["ImageRef"][];
        };
        ImageRef: {
            /** @description id */
            image_id: string;
            /** @description image_kind */
            image_kind: components["schemas"]["image_kind"];
            /** @description id|null */
            event_id: string | null;
            /** @description trusted UTC|null */
            captured_at: string | null;
            /**
             * Format: date-time
             * @description UTC; first server receipt of image status or JPEG, immutable
             */
            received_at: string;
            /** @description UTC|null; file retention deadline once received JPEG; null if no JPEG stored */
            expires_at: string | null;
            /** @description image_status */
            status: components["schemas"]["image_status"];
            /** @description image_error_code|null; null for PENDING/AVAILABLE */
            error_code: components["schemas"]["image_error_code"] | null;
        } & (unknown & unknown);
        CommandView: {
            /** @description id */
            command_id: string;
            /** @description command_type */
            type: components["schemas"]["command_type"];
            /** @description server_command_status */
            server_status: components["schemas"]["server_command_status"];
            /**
             * Format: date-time
             * @description UTC
             */
            issued_at: string;
            /**
             * Format: date-time
             * @description UTC
             */
            expires_at: string;
            /** @description UTC|null */
            result_received_at: string | null;
            /** @description command_result|null */
            result: components["schemas"]["command_result"] | null;
            /** @description error_code|null */
            error_code: components["schemas"]["error_code"] | null;
            /** @description CommandResultData with token removed; see authorized GET command result */
            result_data: components["schemas"]["PublicCommandResultData"];
        } & (unknown & unknown & unknown & unknown);
        EventDetail: {
            /** @description EventRecord */
            event: components["schemas"]["EventRecord"];
            /** @description use_type|null */
            manual_use_type: components["schemas"]["use_type"] | null;
            /** @description integer>=0 */
            last_device_seq: number;
            /** @description array<EventMessage>; chronological */
            messages: components["schemas"]["EventMessage"][];
            /** @description array<Review> */
            reviews: components["schemas"]["Review"][];
            /** @description array<ImageRef> */
            images: components["schemas"]["ImageRef"][];
        };
        Review: {
            /** @description id */
            review_id: string;
            /** @description id */
            event_id: string;
            /** @description id */
            created_by: string;
            /**
             * Format: date-time
             * @description UTC
             */
            created_at: string;
            /** @description string0..500 */
            note: string;
            /** @description use_type|null */
            use_type: components["schemas"]["use_type"] | null;
        };
        RestError: {
            code: components["schemas"]["error_code"] | ("RATE_LIMITED" | "SERVICE_UNAVAILABLE" | "INTERNAL_ERROR");
            /** @description human readable string */
            message: string;
            /** @description object */
            details: {
                [key: string]: JsonValue;
            };
            /** @description boolean */
            retryable: boolean;
            /** @description id */
            request_id: string;
        };
        ImageUpdate: {
            /** @description id */
            image_id: string;
            /** @description image_kind */
            image_kind: components["schemas"]["image_kind"];
            /** @description id|null */
            event_id: string | null;
            /** @description trusted UTC|null; null before capture or when capture clock untrusted */
            captured_at: string | null;
            /** @description image_status */
            status: components["schemas"]["image_status"];
            /** @description image_error_code|null; required nonnull for FAILED/EXPIRED, null otherwise */
            error_code: components["schemas"]["image_error_code"] | null;
        } & unknown;
        ClockStatus: {
            /** @description clock_state */
            state: components["schemas"]["clock_state"];
            /** @description integer>=0|null; valid adapter estimate rounded up, not a measured accuracy guarantee */
            error_bound_ms: number | null;
            /** @description UTC|null; most recent externally synchronized anchor, not latest health-poll time */
            last_sync_at: string | null;
            /** @description integer>=0|null; monotonic elapsed age of same-boot external sync anchor */
            sync_age_ms: number | null;
        } & (unknown & unknown);
        JsonValue: JsonValue;
        Geometry: {
            /** @description array of >=3 [finite x,finite y]; simple polygon */
            roi_polygon_m: [
                number,
                number
            ][];
            /** @description array of >=3 [finite x,finite y]; simple polygon */
            exit_polygon_m: [
                number,
                number
            ][];
            /** @description id */
            calibration_id: string;
            /** @description array>=4 of {image_uv:[0..1,0..1],ground_xy_m:[finite,finite]}; noncollinear */
            reference_points: {
                image_uv: [
                    number,
                    number
                ];
                ground_xy_m: [
                    number,
                    number
                ];
            }[];
        };
        OperatingHours: {
            /**
             * @description const Asia/Seoul
             * @constant
             */
            timezone: "Asia/Seoul";
            /** @description array of {weekday:integer1..7,start_hhmm:HH:mm,end_hhmm:HH:mm or24:00}; overnight split into two windows */
            windows: {
                weekday: number;
                start_hhmm: string;
                end_hhmm: string;
            }[];
        };
        STOP_GUIDANCEParams: {
            /** @description id server assigned */
            pause_id: string;
            /**
             * Format: date-time
             * @description UTC; immediate MUST exactly equal Command.issued_at; scheduled MUST be later than issued_at and scope ZONE. Classify by this equality, never unreliable receiver wall time.
             */
            starts_at: string;
            /** @description UTC|null; pause ending, independent command TTL */
            expires_at: string | null;
            /** @description string1..200 */
            reason: string;
        };
        RESUME_GUIDANCEParams: {
            /** @description nonempty array<id>; exact release list */
            pause_ids: string[];
            /** @description boolean; true only after operator verifies same vehicle for OBSERVATION_REVIEW */
            identity_confirmed: boolean;
            /** @description string1..200; evidence required for identity confirmation */
            reason: string;
        };
        REQUEST_SNAPSHOTParams: {
            /** @description VIEW|RESUME|CLOSE_EVENT */
            purpose: components["schemas"]["snapshot_purpose"];
        };
        CLOSE_EVENTParams: {
            /** @description string1..200 */
            reason: string;
        };
        ActivePause: {
            /** @description id */
            pause_id: string;
            /** @description pause_source */
            source: components["schemas"]["pause_source"];
            /** @description scope */
            scope: components["schemas"]["scope"];
            /** @description id|null; nonnull iff EVENT */
            target_event_id: string | null;
            /** @description UTC|null */
            starts_at: string | null;
            /** @description UTC|null */
            expires_at: string | null;
            /** @enum {unknown} */
            status: "SCHEDULED" | "ACTIVE" | "ACK_REQUIRED";
            /** @description string 1..200 */
            reason: string;
        } & unknown;
        PublicCommandResultData: {
            /** @description array<id>; [] when irrelevant */
            pause_ids: string[];
            /** @description id|null */
            image_id: string | null;
            /** @description Public REST/SSE keeps the field but always returns null. */
            confirmation_token: null;
            /** @description integer0..30000|null */
            token_valid_for_ms: number | null;
            /** @description id|null */
            closed_event_id: string | null;
        };
        AuthorizedCommandView: {
            /** @description id */
            command_id: string;
            /** @description command_type */
            type: components["schemas"]["command_type"];
            /** @description server_command_status */
            server_status: components["schemas"]["server_command_status"];
            /**
             * Format: date-time
             * @description UTC
             */
            issued_at: string;
            /**
             * Format: date-time
             * @description UTC
             */
            expires_at: string;
            /** @description UTC|null */
            result_received_at: string | null;
            /** @description command_result|null */
            result: components["schemas"]["command_result"] | null;
            /** @description error_code|null */
            error_code: components["schemas"]["error_code"] | null;
            result_data: components["schemas"]["CommandResultData"];
        } & (unknown & unknown & unknown & unknown);
        EventMessage: {
            /** @constant */
            schema_version: 2;
            device_id: string;
            zone_id: string;
            message_id: string;
            boot_id: string;
            device_seq: number;
            occurred_at: string | null;
            clock_synced: boolean;
            event_id: string | null;
            event_type: components["schemas"]["event_type"];
            event: components["schemas"]["EventRecord"] | null;
            snapshot: components["schemas"]["DeviceSnapshot"];
            image_update: components["schemas"]["ImageUpdate"] | null;
        } & (unknown & unknown);
        Heartbeat: {
            /** @constant */
            schema_version: 2;
            device_id: string;
            zone_id: string;
            message_id: string;
            boot_id: string;
            device_seq: number;
            occurred_at: string | null;
            clock_synced: boolean;
            event_id: string | null;
            snapshot: components["schemas"]["DeviceSnapshot"];
            fps: number;
            pending_message_count: number;
            storage_ok: boolean;
        } & unknown;
        Availability: {
            device_id: string;
            boot_id: string;
            connection_id: string;
            /** @enum {unknown} */
            status: "ONLINE" | "OFFLINE";
        };
        Ack: {
            /** @constant */
            schema_version: 2;
            device_id: string;
            message_id: string;
            status: components["schemas"]["ack_status"];
            error_code: components["schemas"]["error_code"] | null;
            /** Format: date-time */
            committed_at: string;
        } & unknown;
        ConfigDesired: {
            /** @constant */
            schema_version: 2;
            device_id: string;
            desired_policy: components["schemas"]["Policy"];
        };
        ConfigReported: {
            /** @constant */
            schema_version: 2;
            device_id: string;
            zone_id: string;
            message_id: string;
            boot_id: string;
            device_seq: number;
            occurred_at: string | null;
            clock_synced: boolean;
            event_id: string | null;
            config_status: components["schemas"]["config_status"];
            error_code: components["schemas"]["error_code"] | null;
            snapshot: components["schemas"]["DeviceSnapshot"];
        } & unknown;
        CommandResult: {
            /** @constant */
            schema_version: 2;
            device_id: string;
            zone_id: string;
            message_id: string;
            boot_id: string;
            device_seq: number;
            occurred_at: string | null;
            clock_synced: boolean;
            event_id: string | null;
            command_id: string;
            result: components["schemas"]["command_result"];
            error_code: components["schemas"]["error_code"] | null;
            result_data: components["schemas"]["CommandResultData"];
            snapshot: components["schemas"]["DeviceSnapshot"];
        } & (unknown & unknown);
        LoginRequest: {
            username: string;
            password: string;
        };
        AuthUser: {
            user_id: string;
            role: components["schemas"]["role"];
        };
        CsrfResponse: {
            token: string;
        };
        PolicyInput: {
            zone_id: string;
            geometry: components["schemas"]["Geometry"];
            timers: components["schemas"]["Timers"];
            vision_profile_id: string;
            operating_hours: components["schemas"]["OperatingHours"];
        };
        PolicyPutRequest: {
            expected_version: number;
            policy: components["schemas"]["PolicyInput"];
            reason: string;
        };
        PolicyPutResponse: {
            version: number;
            /** @constant */
            delivery_status: "QUEUED";
        };
        PolicyGetResponse: {
            latest_policy: components["schemas"]["Policy"];
            device_versions: {
                received_policy_version: number | null;
                active_policy_version: number | null;
                event_policy_version: number | null;
            };
        };
        WebStopParams: {
            /** @description null=immediate; server sets MQTT starts_at=issued_at. UTC=future schedule, ZONE only; past/equal request is rejected, never silently made immediate. */
            starts_at: string | null;
            expires_at: string | null;
            reason: string;
        };
        CommandCreateRequest: {
            client_request_id: string;
            type: components["schemas"]["command_type"];
            scope: components["schemas"]["scope"];
            target_event_id: string | null;
            expected_mode_revision: number | null;
            expected_received_policy_version: number | null;
            expected_active_policy_version: number | null;
            expected_event_policy_version: number | null;
            confirmation_token: string | null;
            params: components["schemas"]["WebStopParams"] | components["schemas"]["RESUME_GUIDANCEParams"] | components["schemas"]["REQUEST_SNAPSHOTParams"] | components["schemas"]["CLOSE_EVENTParams"];
        } & (unknown & unknown & unknown & unknown & unknown & unknown & unknown);
        CommandCreateResponse: {
            command_id: string;
            /** @constant */
            status: "QUEUED";
            /** Format: date-time */
            expires_at: string;
        };
        ReviewCreateRequest: {
            client_request_id: string;
            note: string;
            use_type: components["schemas"]["use_type"] | null;
        };
        ReviewCreateResponse: {
            review_id: string;
            manual_use_type: components["schemas"]["use_type"] | null;
        };
        ImageUploadResponse: {
            image_id: string;
            /** Format: date-time */
            stored_at: string;
        };
        EventListItem: {
            /** @description id */
            event_id: string;
            /** @description incident_state */
            state: components["schemas"]["incident_state"];
            /** @description integer>=1 */
            event_policy_version: number;
            /** @description UTC|null */
            started_at: string | null;
            /** @description UTC|null */
            closed_at: string | null;
            /**
             * @description OBSERVED_EXIT|MANUAL|null
             * @enum {unknown}
             */
            close_reason: "OBSERVED_EXIT" | "MANUAL" | null;
            /** @description integer 0..2 */
            guidance_count: number;
            /** @description body_type */
            body_type: components["schemas"]["body_type"];
            /** @description use_type */
            use_type: components["schemas"]["use_type"];
            /** @description integer>=0 */
            continuous_stop_ms: number;
            /** @description boolean; true after unresolved threshold; retained independently of HELD */
            overdue: boolean;
            manual_use_type: components["schemas"]["use_type"] | null;
        } & (unknown & unknown & unknown);
        EventListResponse: {
            items: components["schemas"]["EventListItem"][];
            next_cursor: string | null;
        };
        EventQuery: {
            zone_id: string;
            /** Format: date-time */
            from?: string;
            /** Format: date-time */
            to?: string;
            state?: components["schemas"]["incident_state"];
            cursor?: string;
            /** @default 20 */
            limit: number;
        };
        SseEvent: {
            /** @constant */
            event: "zone_snapshot";
            id: string;
            data: components["schemas"]["ZoneView"];
        };
        ClockBoundaryExamples: {
            condition: string;
            expected: string;
        }[];
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    op01: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CsrfResponse"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 예기치 않은 서버 오류: INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 일시 이용 불가: SERVICE_UNAVAILABLE 또는 기존 업무 코드 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op02: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AuthUser"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 요청/동시 연결 제한: RATE_LIMITED */
            429: {
                headers: {
                    /** @description 재시도까지 기다릴 초 */
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 예기치 않은 서버 오류: INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 일시 이용 불가: SERVICE_UNAVAILABLE 또는 기존 업무 코드 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op03: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 로그아웃 완료. 응답 본문 없음. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 예기치 않은 서버 오류: INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 일시 이용 불가: SERVICE_UNAVAILABLE 또는 기존 업무 코드 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op04: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AuthUser"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 예기치 않은 서버 오류: INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 일시 이용 불가: SERVICE_UNAVAILABLE 또는 기존 업무 코드 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op05: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ZoneView"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 예기치 않은 서버 오류: INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 일시 이용 불가: SERVICE_UNAVAILABLE 또는 기존 업무 코드 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op06: {
        parameters: {
            query: {
                zone_id: string;
                from?: string;
                to?: string;
                state?: components["schemas"]["incident_state"];
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EventListResponse"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 예기치 않은 서버 오류: INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 일시 이용 불가: SERVICE_UNAVAILABLE 또는 기존 업무 코드 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op07: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EventDetail"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 예기치 않은 서버 오류: INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 일시 이용 불가: SERVICE_UNAVAILABLE 또는 기존 업무 코드 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op08: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PolicyGetResponse"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 저장 불가; 성공으로 처리하지 않음 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op09: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PolicyPutRequest"];
            };
        };
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PolicyPutResponse"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 저장 불가; 성공으로 처리하지 않음 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op10: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CommandCreateRequest"];
            };
        };
        responses: {
            /** @description 성공 */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CommandCreateResponse"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 저장 불가; 성공으로 처리하지 않음 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op11: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AuthorizedCommandView"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 저장 불가; 성공으로 처리하지 않음 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op12: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReviewCreateRequest"];
            };
        };
        responses: {
            /** @description 성공 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReviewCreateResponse"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 저장 불가; 성공으로 처리하지 않음 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op13: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "multipart/form-data": {
                    /**
                     * Format: binary
                     * @description JPEG 원본 bytes ≤5 MiB. 서버가 magic/decode/폭·높이·해시를 검사한다.
                     */
                    image: string;
                    meta: components["schemas"]["ImageMetadata"];
                };
            };
        };
        responses: {
            /** @description 성공 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImageUploadResponse"];
                };
            };
            /** @description 성공 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImageUploadResponse"];
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 저장 불가; 성공으로 처리하지 않음 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op14: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["schemas"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 권한 검사 후 JPEG */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "image/jpeg": string;
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 저장 불가; 성공으로 처리하지 않음 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    op15: {
        parameters: {
            query: {
                zone_id: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description SSE 프레임의 data는 ZoneView JSON. 연결 후와 재연결 후 REST 최신 조회. id=view_revision 십진 문자열. keepalive 주석은 상태 이벤트가 아님. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example event: zone_snapshot
                     *     id: 8
                     *     data: <ZoneView JSON>
                     */
                    "text/event-stream": string;
                };
            };
            /** @description 요청 스키마 또는 의미 검증 실패(INVALID_SCHEMA 등) */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 인증 필요 */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 권한 또는 CSRF 거부 */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 대상 또는 아직 저장되지 않은 사진 없음 */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 버전·상태·ID·확인 토큰 충돌 */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 보관기한 종료 */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 요청/동시 연결 제한: RATE_LIMITED */
            429: {
                headers: {
                    /** @description 재시도까지 기다릴 초 */
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 예기치 않은 서버 오류: INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 일시 이용 불가: SERVICE_UNAVAILABLE 또는 기존 업무 코드 */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RestError"];
                };
            };
            /** @description 프록시 또는 네트워크 오류는 JSON 본문을 보장하지 않는다. 미확인 상태로 처리하고 상태를 다시 조회한다. */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
