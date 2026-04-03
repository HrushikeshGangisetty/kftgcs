"""
URL configuration for pavaman_gcs project.

The `urlpatterns` list routes URLs to views. For more information please see:
    https://docs.djangoproject.com/en/6.0/topics/http/urls/
Examples:
Function views
    1. Add an import:  from my_app import views
    2. Add a URL to urlpatterns:  path('', views.home, name='home')
Class-based views
    1. Add an import:  from other_app.views import Home
    2. Add a URL to urlpatterns:  path('', Home.as_view(), name='home')
Including another URLconf
    1. Import the include() function: from django.urls import include, path
    2. Add a URL to urlpatterns:  path('blog/', include('blog.urls'))
"""
from django.contrib import admin
from django.urls import path
from pavaman_gcs_app.graphs import ( cards_total_summary, flight_count_by_date_filter, live_drone_tracking, mission_replay,
 pilot_drone_mission_full_history, top_5_pilots_by_flight_hours, vehicle_mission_hours, weekly_flights_by_date)
from pavaman_gcs_app.views import ( add_admin, admin_forgot_password, admin_forgot_verify_otp, admin_logout, admin_password_login, admin_resend_otp, admin_reset_password, admin_send_otp, admin_verify_otp,
complete_admin_registration, device_notifications, exit_admin_view, get_mission_events,pilot_details,registration_approved_rejected, switch_to_admin, update_admin_status, update_vehicle_uin,
view_all_admins, view_all_missions, view_missions, view_pilots,view_pilots_and_vehicles )

from pavaman_gcs_app.pilot_views import (admin_send_device_otp, admin_verify_and_approve_device, pilot_login, pilot_logout, pilot_register,
resend_pilot_verification_otp, verify_pilot_otp)

urlpatterns = [
    path('api/admin/', admin.site.urls),

    path('api/admin-send-otp',admin_send_otp, name='admin_send_otp'),
    path('api/admin-resend-otp',admin_resend_otp,name='admin_resend_otp'),
    path('api/admin-password-login', admin_password_login, name='admin_password_login'),
    path('api/admin-logout',admin_logout,name='admin_logout'),
    path('api/admin-verify-otp', admin_verify_otp, name='admin_verify_otp'),
    path('api/add-admin', add_admin, name='add_admin'),
    path('api/admin-registration/<uuid:link>/', complete_admin_registration, name='complete_admin_registration'),
    path('api/update-admin-status', update_admin_status, name='update_admin_status'),
    path('api/view-all-admins', view_all_admins, name='view_all_admins'),

    path('api/switch-to-admin', switch_to_admin, name='switch_to_admin'),
    path('api/exit-admin-view', exit_admin_view, name='exit_admin_view'),




    path('api/update-vehicle-uin',update_vehicle_uin,name='update_vehicle_uin'),
    path('api/view-missions',view_missions,name='view_missions'),
    path('api/get-mission-events',get_mission_events,name='get_mission_events'),
    # path('api/drone-flying-details',drone_flying_details,name='drone_flying_details'),
    # path('api/drone-spray-area-plot',drone_spray_area_plot,name='drone_spray_area_plot'),
    # path('api/drone-battery-status',drone_battery_status,name='drone_battery_status'),
    # path('api/drone-position',drone_position,name='drone_position'),
    # path('api/drone-flight-status-details',drone_flight_status_details,name='drone_flight_status_details'),
    path('api/device-notifications',device_notifications,name='device_notifications'),

    path('api/registration-approved-rejected', registration_approved_rejected, name='registration_approved_rejected'),
    path('api/pilot-details',pilot_details,name='pilot_details'),

    path('api/pilot-register', pilot_register, name='pilot_register'),
    path('api/verify-otp', verify_pilot_otp, name='verify_pilot_otp'),
    path('api/resend-otp',resend_pilot_verification_otp,name="resend_pilot_verification_otp"),
    path('api/pilot-login',pilot_login, name="pilot_login"),
    path('api/view-pilots', view_pilots, name= 'view_pilots'),
    path('api/pilot-logout',pilot_logout, name="pilot_logout"),
    # path('api/admin_verify_device_otp',admin_verify_device_otp,name='admin_verify_device_otp'),
    path('api/admin-send-device-otp',admin_send_device_otp,name="admin_send_device_otp"),
    path('api/admin-verify-and-approve-device',admin_verify_and_approve_device,name="admin_verify_and_approve_device"),

    path('api/weekly-flights-by-date', weekly_flights_by_date, name='weekly_flights_by_date'),
    # path('api/weekly-flights-per-drone',weekly_flights_per_drone, name='weekly_flights_per_drone'),
    # path('api/weekly-flights-per-pilot',weekly_flights_per_pilot, name='weekly_flights_per_pilot'),
    path('api/flight-count-by-date-filter',flight_count_by_date_filter, name='flight_count_by_date_filter'),
    path('api/cards-total-summary',cards_total_summary, name='cards_total_summary'),
    path('api/view-all-missions',view_all_missions, name='view_all_missions'),
    path('api/vehicle-mission-hours',vehicle_mission_hours, name='vehicle_mission_hours'),
    path('api/live-drone-tracking',live_drone_tracking, name='live_drone_tracking'),
    path('api/view-pilots-and-vehicles', view_pilots_and_vehicles, name='view_pilots_and_vehicles'),
    path("api/top-5-pilots", top_5_pilots_by_flight_hours, name="top_5_pilots_by_flight_hours"),
    path('api/pilot-drone-mission-full-history', pilot_drone_mission_full_history, name='pilot_drone_mission_full_history'),
    # path('api/offline-tracking-history', offline_tracking_history, name='offline_tracking_history'),
    path('api/mission-replay', mission_replay, name='mission_replay'),



    path('api/admin-forgot-password', admin_forgot_password, name='admin_forgot_password'),
    path('api/admin-forgot-verify-otp', admin_forgot_verify_otp, name='admin_forgot_verify_otp'),
    path('api/admin-reset-password', admin_reset_password, name='admin_reset_password')
]