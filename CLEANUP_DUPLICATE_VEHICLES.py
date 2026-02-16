"""
Clean up duplicate vehicle entries in the database

This script helps resolve the IntegrityError: duplicate key value violates unique constraint
"pavaman_gcs_app_vehicle_vehicle_name_key" error by removing duplicate DRONE_01 entries.

Usage:
1. SSH to your server: ssh -i your-key.pem ubuntu@65.0.76.31
2. cd to your Django project
3. Activate virtualenv: source venv/bin/activate
4. Run: python manage.py shell
5. Copy-paste the code below

⚠️  WARNING: This will delete duplicate vehicle records. Make sure you backup your database first!
"""

# === PASTE THIS IN DJANGO SHELL ===

from pavaman_gcs_app.models import Vehicle
from django.db import transaction

print("=" * 60)
print("CLEANING UP DUPLICATE VEHICLE ENTRIES")
print("=" * 60)

# Check for duplicate DRONE_01 entries
print("\n📋 CHECKING FOR DUPLICATE VEHICLES:")
all_vehicles = Vehicle.objects.all()
print(f"Total vehicles in database: {all_vehicles.count()}")

# Group by vehicle_name to find duplicates
from collections import defaultdict
vehicle_groups = defaultdict(list)

for vehicle in all_vehicles:
    vehicle_groups[vehicle.vehicle_name].append(vehicle)
    print(f"  - Vehicle: {vehicle.vehicle_name} (id={vehicle.id})")

print("\n" + "=" * 60)

# Identify duplicates
duplicates_found = False
for vehicle_name, vehicles in vehicle_groups.items():
    if len(vehicles) > 1:
        duplicates_found = True
        print(f"🔍 DUPLICATE FOUND: '{vehicle_name}' has {len(vehicles)} entries:")
        for i, vehicle in enumerate(vehicles):
            print(f"  [{i+1}] id={vehicle.id}, created={getattr(vehicle, 'created_at', 'N/A')}")

        print(f"\n💡 RECOMMENDATION: Keep the oldest entry, delete others")

        # Sort by ID (oldest first) and keep the first one
        vehicles.sort(key=lambda v: v.id)
        vehicle_to_keep = vehicles[0]
        vehicles_to_delete = vehicles[1:]

        print(f"   ✅ KEEP: id={vehicle_to_keep.id}")
        for vehicle in vehicles_to_delete:
            print(f"   ❌ DELETE: id={vehicle.id}")

        # Uncomment the lines below to actually delete duplicates
        print(f"\n   🚨 TO DELETE DUPLICATES, UNCOMMENT THE LINES BELOW AND RUN AGAIN:")
        print(f"   # with transaction.atomic():")
        for vehicle in vehicles_to_delete:
            print(f"   #     Vehicle.objects.get(id={vehicle.id}).delete()")
        print(f"   #     print('Deleted vehicle id={vehicle.id}')")

if not duplicates_found:
    print("✅ No duplicate vehicles found!")

print("\n" + "=" * 60)
print("SUMMARY:")
print(f"- Total vehicles: {all_vehicles.count()}")
print(f"- Duplicates found: {duplicates_found}")
print("\nℹ️  The Android app now uses unique vehicle names, so new connections won't create duplicates.")
print("ℹ️  But you should clean up existing duplicates to avoid confusion.")
