"""
Django Models - Updated version with total_acres instead of total_distance_meters
Copy this to your Django backend: pavaman_gcs_app/models.py

Changes made:
- MissionSummary: Changed total_distance_meters to total_acres
"""

from django.db import models
from django.utils import timezone
import uuid


class SuperAdmin(models.Model):
    name = models.CharField(max_length=50)
    email = models.EmailField(max_length=50, unique=True, db_index=True)
    mobile_no = models.CharField(max_length=15, unique=True, db_index=True)
    otp = models.IntegerField(null=True, blank=True)
    otp_send_type = models.CharField(max_length=50, null=True, blank=True)
    changed_on = models.DateTimeField(null=True, blank=True)
    password = models.CharField(max_length=255)
    status = models.IntegerField(default=1)

    def __str__(self):
        return self.name

class Admin(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)
    name = models.CharField(max_length=50,null= True,blank=True) #company name
    contact_name=  models.CharField(max_length=50,null= True,blank=True)
    email = models.EmailField(max_length=50, unique=True, db_index=True)
    mobile_no = models.CharField(max_length=15, unique=True, db_index=True,null= True,blank=True)
    otp = models.IntegerField(null=True, blank=True)
    otp_send_type = models.CharField(max_length=50, null=True, blank=True)
    changed_on = models.DateTimeField(null=True, blank=True)
    password = models.CharField(max_length=255)
    drones= models.IntegerField(default=0)
    pilots=models.IntegerField(default=0)
    logo_path=models.CharField(max_length=250,null= True,blank=True)

    gst_file_path=models.CharField(max_length=250,null= True,blank=True)
    address=models.TextField(max_length=250,null= True,blank=True)
    approval=models.IntegerField(default=0)
    invite_link= models.UUIDField(default=uuid.uuid4,null= True,blank=True)
    created_on = models.DateTimeField(default=timezone.now)
    is_superadmin_company = models.BooleanField(default=False)


    status = models.IntegerField(default=1)
    landmark = models.CharField(max_length=150, null=True, blank=True)
    city = models.CharField(max_length=100, null=True, blank=True)
    state = models.CharField(max_length=100, null=True, blank=True)
    pincode = models.CharField(max_length=10, null=True, blank=True)

    def __str__(self):
        return self.name

class Pilot(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null=True, blank=True)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE)
    company_name=models.CharField(max_length=50,null=True, blank=True)
    first_name = models.CharField(max_length=50)
    last_name = models.CharField(max_length=50)
    email = models.EmailField(max_length=50, unique=True, db_index=True)
    mobile_no = models.CharField(max_length=15, unique=True, db_index=True)
    password = models.CharField(max_length=255)
    login_on = models.DateTimeField(null=True, blank=True)
    login_status = models.IntegerField(default=0)
    logout_on = models.DateTimeField(null=True, blank=True)
    logout_status = models.IntegerField(default=0)
    device_id = models.CharField(max_length=255, null=True, blank=True)
    device_limit = models.IntegerField(default=1)
    register_status = models.IntegerField(default=0)
    admin_approved = models.IntegerField(default=0)
    created_on = models.DateTimeField(default=timezone.now)
    status = models.IntegerField(default=1)
    otp = models.IntegerField(null=True, blank=True)
    changed_on = models.DateTimeField(null=True, blank=True)

    def __str__(self):
        return f"{self.first_name} {self.last_name}"
class PilotDeviceAccess(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    admin = models.ForeignKey(Admin, on_delete=models.CASCADE)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE, related_name="device_requests")
    ip_address = models.CharField(max_length=255)
    admin_approved = models.IntegerField(default=0)
    is_active = models.BooleanField(default=False)
    is_read = models.BooleanField(default=False)
    otp = models.IntegerField(null=True, blank=True)
    changed_on = models.DateTimeField(null=True, blank=True)
    device_status= models.CharField(max_length=20, default="NEW")
    created_on = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = ("pilot", "ip_address")


class Vehicle(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    admin = models.ForeignKey(Admin, on_delete=models.CASCADE, null=True, blank=True)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE, null=True, blank=True)
    vehicle_id = models.CharField(max_length=100, db_index=True)
    vehicle_name = models.CharField(max_length=100)
    uin = models.CharField(max_length=100, db_index=True, null=True, blank=True)
    registered_date = models.DateField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)


    def __str__(self):
        return self.vehicle_name

class Mission(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    mission_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)

    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE, null=True, blank=True)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE, default=1)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)

    start_time = models.DateTimeField()
    end_time = models.DateTimeField(null=True, blank=True)

    STATUS_CREATED = 0
    STATUS_STARTED = 1
    STATUS_PAUSED = 2
    STATUS_RESUMED = 3
    STATUS_ENDED = 4

    STATUS_CHOICES = [
        (STATUS_CREATED, "Created"),
        (STATUS_STARTED, "Started"),
        (STATUS_PAUSED, "Paused"),
        (STATUS_RESUMED, "Resumed"),
        (STATUS_ENDED, "Ended"),
    ]

    status = models.IntegerField(choices=STATUS_CHOICES, default=STATUS_CREATED)

    # Pause / Resume timestamps
    paused_at = models.DateTimeField(null=True, blank=True)
    resumed_at = models.DateTimeField(null=True, blank=True)
    plot_name = models.CharField(max_length=255, null=True, blank=True)

    # ============================================================
    # 🔥 NEW FIELDS: Flight Mode, Mission Type, Grid Setup Source
    # ============================================================

    # Flight mode: AUTOMATIC or MANUAL
    FLIGHT_MODE_AUTOMATIC = 'AUTOMATIC'
    FLIGHT_MODE_MANUAL = 'MANUAL'

    FLIGHT_MODE_CHOICES = [
        (FLIGHT_MODE_AUTOMATIC, 'Automatic'),
        (FLIGHT_MODE_MANUAL, 'Manual'),
    ]

    flight_mode = models.CharField(
        max_length=20,
        choices=FLIGHT_MODE_CHOICES,
        default=FLIGHT_MODE_AUTOMATIC,
        null=True,
        blank=True,
        help_text="Flight mode selected by user: AUTOMATIC or MANUAL"
    )

    # Mission type: GRID, WAYPOINT, or NONE
    MISSION_TYPE_GRID = 'GRID'
    MISSION_TYPE_WAYPOINT = 'WAYPOINT'
    MISSION_TYPE_NONE = 'NONE'

    MISSION_TYPE_CHOICES = [
        (MISSION_TYPE_GRID, 'Grid'),
        (MISSION_TYPE_WAYPOINT, 'Waypoint'),
        (MISSION_TYPE_NONE, 'None'),
    ]

    mission_type = models.CharField(
        max_length=20,
        choices=MISSION_TYPE_CHOICES,
        default=MISSION_TYPE_NONE,
        null=True,
        blank=True,
        help_text="Mission type: GRID, WAYPOINT, or NONE"
    )

    # Grid setup source: KML_IMPORT, MAP_DRAW, DRONE_POSITION, RC_CONTROL, or NONE
    GRID_SOURCE_KML_IMPORT = 'KML_IMPORT'
    GRID_SOURCE_MAP_DRAW = 'MAP_DRAW'
    GRID_SOURCE_DRONE_POSITION = 'DRONE_POSITION'
    GRID_SOURCE_RC_CONTROL = 'RC_CONTROL'
    GRID_SOURCE_NONE = 'NONE'

    GRID_SOURCE_CHOICES = [
        (GRID_SOURCE_KML_IMPORT, 'KML Import'),
        (GRID_SOURCE_MAP_DRAW, 'Map Draw'),
        (GRID_SOURCE_DRONE_POSITION, 'Drone Position'),
        (GRID_SOURCE_RC_CONTROL, 'RC Control'),
        (GRID_SOURCE_NONE, 'None'),
    ]

    grid_setup_source = models.CharField(
        max_length=20,
        choices=GRID_SOURCE_CHOICES,
        default=GRID_SOURCE_NONE,
        null=True,
        blank=True,
        help_text="Grid setup source: KML_IMPORT, MAP_DRAW, DRONE_POSITION, RC_CONTROL, or NONE"
    )
    class Meta:
        indexes = [
            models.Index(fields=["admin", "status", "start_time"]),
            models.Index(fields=["superadmin", "status", "start_time"]),
            models.Index(fields=["pilot", "status", "start_time"]),
            models.Index(fields=["vehicle", "status", "start_time"]),
            models.Index(fields=["start_time", "end_time"]),
        ]
    # ============================================================

    def __str__(self):
        return f"Mission {self.mission_id}"


class TelemetryPosition(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE, null=True, blank=True)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE, null=True, blank=True)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)
    mission = models.ForeignKey(Mission, on_delete=models.CASCADE, related_name="positions")
    ts = models.DateTimeField()

    lat = models.FloatField()
    lng = models.FloatField()
    alt = models.FloatField()
    speed = models.FloatField(null=True, blank=True)

    class Meta:
        db_table = "pavaman_gcs_app_telemetry_position"
        indexes = [models.Index(fields=["mission", "ts"])]


class TelemetryBattery(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE, null=True, blank=True)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE, null=True, blank=True)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)
    mission = models.ForeignKey(Mission, on_delete=models.CASCADE, related_name="battery")
    ts = models.DateTimeField()

    voltage = models.FloatField(null=True, blank=True)
    current = models.FloatField(null=True, blank=True)
    remaining = models.FloatField(null=True, blank=True)

    class Meta:
        db_table = "pavaman_gcs_app_telemetry_battery"
        indexes = [models.Index(fields=["mission", "ts"])]


class TelemetryAttitude(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE, null=True, blank=True)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE, null=True, blank=True)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)
    mission = models.ForeignKey(Mission, on_delete=models.CASCADE, related_name="attitude")
    ts = models.DateTimeField()

    roll = models.FloatField()
    pitch = models.FloatField()
    yaw = models.FloatField()

    class Meta:
        db_table = "pavaman_gcs_app_telemetry_attitude"
        indexes = [models.Index(fields=["mission", "ts"])]


class TelemetryGPS(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE, null=True, blank=True)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE, null=True, blank=True)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)
    mission = models.ForeignKey(Mission, on_delete=models.CASCADE, related_name="gps")
    ts = models.DateTimeField()

    satellites = models.IntegerField()
    hdop = models.FloatField()

    class Meta:
        db_table = "pavaman_gcs_app_telemetry_gps"
        indexes = [models.Index(fields=["mission", "ts"])]


class TelemetryStatus(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE, null=True, blank=True)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE, null=True, blank=True)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)
    mission = models.ForeignKey(
        Mission,
        on_delete=models.CASCADE,
        related_name="telemetry_status"
    )
    ts = models.DateTimeField()

    flight_mode = models.CharField(max_length=50)
    armed = models.BooleanField()
    failsafe = models.BooleanField()

    class Meta:
        db_table = "pavaman_gcs_app_telemetry_status"
        indexes = [
            models.Index(fields=["mission", "ts"]),
        ]


class TelemetrySpray(models.Model):
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)

    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE, null=True, blank=True)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE, null=True, blank=True)
    mission = models.ForeignKey(
        Mission,
        on_delete=models.CASCADE,
        related_name="spray"
    )
    ts = models.DateTimeField()

    spray_on = models.BooleanField()
    spray_rate_lpm = models.FloatField(null=True, blank=True)
    flow_pulse = models.IntegerField(null=True, blank=True)
    tank_level_liters = models.FloatField(null=True, blank=True)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)

    class Meta:
        db_table = "pavaman_gcs_app_telemetry_spray"
        indexes = [
            models.Index(fields=["mission", "ts"]),
        ]


class MissionEvent(models.Model):
    EVENT_INFO = "INFO"
    EVENT_WARNING = "WARNING"
    EVENT_ERROR = "ERROR"

    EVENT_STATUS_CHOICES = [
        (EVENT_INFO, "Info"),
        (EVENT_WARNING, "Warning"),
        (EVENT_ERROR, "Error"),
    ]

    EVENT_TYPES = [
        ("MISSION_STARTED", "Mission Started"),
        ("LOW_BATTERY", "Low Battery"),
        ("MISSION_PAUSED", "Mission Paused"),
        ("BATTERY_REPLACED", "Battery Replaced"),
        ("MISSION_RESUMED", "Mission Resumed"),
        ("MISSION_ENDED", "Mission Ended"),
    ]

    mission = models.ForeignKey(
        Mission,
        on_delete=models.CASCADE,
        related_name="events"
    )
    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE)
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)


    event_type = models.CharField(max_length=50, choices=EVENT_TYPES)
    event_status = models.CharField(max_length=20, choices=EVENT_STATUS_CHOICES)
    event_description = models.TextField()

    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "pavaman_gcs_app_mission_event"

    def __str__(self):
        return f"{self.event_type} - {self.event_status}"


class MissionSummary(models.Model):
    STATUS_COMPLETED = "COMPLETED"
    STATUS_FAILED = "FAILED"

    STATUS_CHOICES = [
        (STATUS_COMPLETED, "Completed"),
        (STATUS_FAILED, "Failed"),
    ]

    mission = models.OneToOneField(
        Mission,
        on_delete=models.CASCADE,
        related_name="summary"
    )
    vehicle = models.ForeignKey(Vehicle, on_delete=models.CASCADE)
    pilot = models.ForeignKey(Pilot, on_delete=models.CASCADE)
    admin = models.ForeignKey(Admin, on_delete=models.CASCADE)
    superadmin = models.ForeignKey(SuperAdmin, on_delete=models.CASCADE, null= True,blank=True)


    # CHANGED: total_distance_meters -> total_acres
    total_acres = models.FloatField(null=True, blank=True, help_text="Total area covered in acres")
    total_sprayed_acres = models.FloatField(null=True, blank=True, help_text="Total area actually sprayed in acres", default=0.0)
    total_spray_used_liters = models.FloatField(null=True, blank=True)
    flying_time_sec = models.FloatField(null=True, blank=True)

    battery_start = models.IntegerField(null=True, blank=True)
    battery_end = models.IntegerField(null=True, blank=True)
    project_name = models.CharField(max_length=255, null=True, blank=True, help_text="Project name entered by user")
    plot_name = models.CharField(max_length=255, null=True, blank=True, help_text="Plot name entered by user")
    crop_type = models.CharField(max_length=100, null=True, blank=True, help_text="Crop type (e.g., Wheat, Rice, Cotton)")

    status = models.CharField(
        max_length=20,
        choices=STATUS_CHOICES,
        default=STATUS_COMPLETED
    )

    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "pavaman_gcs_app_mission_summary"
        indexes = [
            models.Index(fields=["superadmin", "created_at"]),  # Added superadmin
            models.Index(fields=["admin", "created_at"]),
            models.Index(fields=["pilot", "created_at"]),
            models.Index(fields=["vehicle", "created_at"]),
            models.Index(fields=["created_at"]),
        ]

    def __str__(self):
        return f"Summary of {self.mission_id}"