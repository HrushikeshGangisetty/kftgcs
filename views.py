import traceback
from django.conf import settings
from django.shortcuts import render
from django.views.decorators.csrf import csrf_exempt
from django.http import JsonResponse

from .models import Admin, Mission, Pilot, PilotDeviceAccess, SuperAdmin, TelemetryAttitude, TelemetryBattery, TelemetryGPS, TelemetryPosition, TelemetrySpray, TelemetryStatus, Vehicle
import json
import random
from django.db import IntegrityError
from django.utils import timezone
from .msg91 import send_verify_mobile
import json
import random
from datetime import timedelta, datetime
import uuid

from django.core.mail import EmailMultiAlternatives
from django.utils.timezone import now
from .models import Pilot
from decouple import config
import requests
import pytz
from .indiantime import format_datetime_ist
from django.contrib.auth.hashers import make_password,check_password

import boto3
import uuid
import traceback

# @csrf_exempt
# def add_admin(request):
#     if request.method == "POST":
#         try:

#             data = json.loads(request.body)

#             superadmin_id = request.session.get("superadmin_id") or data.get("superadmin_id")
#             if not superadmin_id:
#                 return JsonResponse({"error": "Unauthorized Login"}, status=401)
#             superadmin=SuperAdmin.objects.filter(id=superadmin_id)
#             if superadmin:
#                 return JsonResponse({"error": "Superadmin doesn't exists"}, status=409)

#             email = data.get('email')

#             if not email:
#                 return JsonResponse({"error": "Email is required"}, status=400)

#             if Admin.objects.filter(email=email).exists():
#                 return JsonResponse({"error": "Email already exists"}, status=409)

#             admin = Admin.objects.create(email=email,superadmin=superadmin)

#             invite_link = f"http://127.0.0.1:3000/admin-register/{admin.invite_link}/"

#             email_sent = send_admin_invite_email(email, invite_link)

#             if not email_sent:
#                 return JsonResponse({"error": "Failed to send email"}, status=500)

#             return JsonResponse({
#                 "message": "Invite sent successfully",
#                 # "invite_link": invite_link  # remove in production
#             }, status=201)

#         except Exception as e:
#             return JsonResponse({"error": str(e)}, status=500)



@csrf_exempt
def add_admin(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        role = request.session.get("role")
        user_id = request.session.get("user_id")

        if not role or not user_id:
            return JsonResponse({"error": "Unauthorized. Please login."}, status=401)

        if role != "superadmin":
            return JsonResponse({"error": "Only superadmins can add admins."}, status=403)

        superadmin = SuperAdmin.objects.filter(id=user_id).first()
        if not superadmin:
            return JsonResponse({"error": "Superadmin doesn't exist"}, status=404)

        data = json.loads(request.body)
        email = data.get('email')
        if not email:
            return JsonResponse({"error": "Email is required"}, status=400)

        if Admin.objects.filter(email=email).exists():
            return JsonResponse({"error": "Email already exists"}, status=409)

        admin = Admin.objects.create(email=email, superadmin=superadmin)

        invite_link = f"http://127.0.0.1:3000/admin-register/{admin.invite_link}/"

        email_sent = send_admin_invite_email(email, invite_link)
        if not email_sent:
            return JsonResponse({"error": "Failed to send email"}, status=500)

        return JsonResponse({
            "message": "Invite sent successfully",
        }, status=201)

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

def send_admin_invite_email(email, invite_link):
    subject = "[Pavaman] Complete Your Admin Registration"

    logo_url = f"{settings.AWS_S3_BUCKET_URL}/aviation-logo.png" if hasattr(settings, 'AWS_S3_BUCKET_URL') else ""

    text_content = f"""
    Hello,
    Please complete your admin registration using the link below:
    {invite_link}
    """

    html_content = f"""
    <html>
    <head>
        <style>
            @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap');

            @media only screen and (max-width: 600px) {{
                .container {{
                    width: 90% !important;
                    padding: 20px !important;
                }}
                .logo {{
                    max-width: 180px !important;
                }}
            }}

            .btn {{
                display: inline-block;
                padding: 12px 24px;
                background-color: #4450A2;
                color: #ffffff !important;
                text-decoration: none;
                border-radius: 8px;
                font-weight: 600;
                margin-top: 20px;
            }}
        </style>
    </head>

    <body style="margin:0; padding:0; font-family:'Inter', sans-serif; background-color:#f5f5f5;">

        <div class="container" style="margin:40px auto; background:#ffffff; border-radius:20px; box-shadow:0 4px 20px rgba(0,0,0,0.1); padding:40px 30px; max-width:480px;">

            <div style="text-align:center;">
                <img src="{logo_url}" alt="Pavaman Logo" class="logo" style="max-width:280px; margin-bottom:20px;" />
                <h2 style="color:#222;">Admin Registration</h2>
            </div>

            <p style="color:#555; font-size:14px;">
                Hello,
            </p>

            <p style="color:#555;">
                You have been invited to register as an Admin on <b>Pavaman Aviation</b>.
                Click the button below to complete your registration.
            </p>

            <div style="text-align:center;">
                <a href="{invite_link}" class="btn">Complete Registration</a>
            </div>

            <p style="color:#888; font-size:13px; margin-top:25px;">
                If the button doesn’t work, copy and paste this link into your browser:
                <br/>
                <a href="{invite_link}" style="color:#4450A2;">{invite_link}</a>
            </p>

            <p style="color:#888; font-size:14px; margin-top:20px;">
                This link may expire for security reasons.
            </p>

            <p style="margin-top:30px; font-size:13px; color:#888;">
                Disclaimer: This is an automated email. Please do not reply.
            </p>

        </div>
    </body>
    </html>
    """

    try:
        email_message = EmailMultiAlternatives(
            subject,
            text_content,
            settings.DEFAULT_FROM_EMAIL,
            [email]
        )
        email_message.attach_alternative(html_content, "text/html")
        email_message.send()
        return True

    except Exception as e:
        print("EMAIL ERROR:", e)
        print(traceback.format_exc())
        return False

def upload_to_s3(file_obj, folder, file_type):
    import uuid
    import boto3
    from django.conf import settings

    s3 = boto3.client(
        's3',
        aws_access_key_id=settings.AWS_ACCESS_KEY_ID,
        aws_secret_access_key=settings.AWS_SECRET_ACCESS_KEY,
        region_name=settings.AWS_S3_REGION_NAME
    )

    file_name = f"document/{folder}/{file_type}_{uuid.uuid4()}_{file_obj.name}"

    s3.upload_fileobj(
        file_obj,
        settings.AWS_STORAGE_BUCKET_NAME,
        file_name,
        ExtraArgs={'ContentType': file_obj.content_type}
    )

    return file_name


# @csrf_exempt
# def complete_admin_registration(request, link):
#     if request.method == "POST":
#         try:
#             admin = Admin.objects.filter(invite_link=link).first()

#             if not admin:
#                 return JsonResponse({"error": "Invalid or expired link"}, status=404)

#             if admin.approval == 1:
#                 return JsonResponse({"error": "Account already registered"}, status=400)

#             name = request.POST.get('name').upper()
#             contact_name = request.POST.get('contact_name')
#             mobile_no = request.POST.get('mobile_no')
#             password = request.POST.get('password')
#             re_password = request.POST.get('re_password')

#             address = request.POST.get('address')
#             pilots = request.POST.get('pilots')
#             drones = request.POST.get('drones')

#             logo = request.FILES.get('logo')
#             gst_file = request.FILES.get('gst_file')


#             if not all([name, contact_name, mobile_no, password, re_password]):
#                 return JsonResponse({"error": "All required fields must be filled"}, status=400)

#             if password != re_password:
#                 return JsonResponse({"error": "Passwords do not match"}, status=400)

#             try:
#                 pilots = int(pilots) if pilots else 0
#                 drones = int(drones) if drones else 0
#             except ValueError:
#                 return JsonResponse({"error": "Pilots and Drones must be numbers"}, status=400)

#             folder_name = f"{admin.name}_{admin.id}"

#             if logo:
#                 admin.logo_path = upload_to_s3(logo, folder_name, "logo")

#             if gst_file:
#                 admin.gst_file_path = upload_to_s3(gst_file, folder_name, "gst")

#             admin.name = name
#             admin.contact_name = contact_name
#             admin.mobile_no = mobile_no
#             admin.address = address
#             admin.pilots = pilots
#             admin.drones = drones
#             admin.password = make_password(password)
#             admin.approval = 1

#             admin.save()

#             return JsonResponse({
#                 "message": "Registration completed. Waiting for approval.",
#                 "data": {
#                     "name": admin.name,
#                     "email": admin.email,
#                     "mobile_no": admin.mobile_no,
#                     "address": admin.address,
#                     "pilots": admin.pilots,
#                     "drones": admin.drones,
#                     "logo_url": f"{settings.AWS_S3_BUCKET_URL}/{admin.logo_path}" if admin.logo_path else None,
#                     "gst_url": f"{settings.AWS_S3_BUCKET_URL}/{admin.gst_file_path}" if admin.gst_file_path else None
#                 }
#             }, status=200)

#         except Exception as e:
#             return JsonResponse({"error": str(e)}, status=500)

#     return JsonResponse({"error": "Invalid HTTP method"}, status=405)



@csrf_exempt
def complete_admin_registration(request, link):
    if request.method == "POST":
        try:
            admin = Admin.objects.filter(invite_link=link).first()

            if not admin:
                return JsonResponse({"error": "Invalid or expired link"}, status=404)

            if admin.approval == 1:
                return JsonResponse({"error": "Account already registered"}, status=400)

            name = request.POST.get('name')
            contact_name = request.POST.get('contact_name')
            mobile_no = request.POST.get('mobile_no')
            password = request.POST.get('password')
            re_password = request.POST.get('re_password')

            address = request.POST.get('address')

            # ✅ NEW FIELDS (ADDED ONLY)
            landmark = request.POST.get('landmark')
            city = request.POST.get('city')
            state = request.POST.get('state')
            pincode = request.POST.get('pincode')

            pilots = request.POST.get('pilots')
            drones = request.POST.get('drones')

            logo = request.FILES.get('logo')
            gst_file = request.FILES.get('gst_file')

            if not all([name, contact_name, mobile_no, password, re_password]):
                return JsonResponse({"error": "All required fields must be filled"}, status=400)

            name = name.upper()

            if password != re_password:
                return JsonResponse({"error": "Passwords do not match"}, status=400)

            try:
                pilots = int(pilots) if pilots else 0
                drones = int(drones) if drones else 0
            except ValueError:
                return JsonResponse({"error": "Pilots and Drones must be numbers"}, status=400)

            folder_name = f"{admin.name}_{admin.id}"

            if logo:
                admin.logo_path = upload_to_s3(logo, folder_name, "logo")

            if gst_file:
                admin.gst_file_path = upload_to_s3(gst_file, folder_name, "gst")

            admin.name = name
            admin.contact_name = contact_name
            admin.mobile_no = mobile_no
            admin.pilots = pilots
            admin.drones = drones

            # ✅ SAVE ADDRESS FIELDS INDIVIDUALLY
            admin.address = address
            admin.landmark = landmark
            admin.city = city
            admin.state = state
            admin.pincode = pincode

            admin.password = make_password(password)
            admin.approval = 1

            admin.save()

            return JsonResponse({
                "message": "Registration completed. Waiting for approval.",
                "data": {
                    "name": admin.name,
                    "email": admin.email,
                    "mobile_no": admin.mobile_no,
                    "address": admin.address,
                    "pilots": admin.pilots,
                    "drones": admin.drones,
                    "logo_url": f"{settings.AWS_S3_BUCKET_URL}/{admin.logo_path}" if admin.logo_path else None,
                    "gst_url": f"{settings.AWS_S3_BUCKET_URL}/{admin.gst_file_path}" if admin.gst_file_path else None
                }
            }, status=200)

        except Exception as e:
            return JsonResponse({"error": str(e)}, status=500)

    return JsonResponse({"error": "Invalid HTTP method"}, status=405)




# @csrf_exempt
# def update_admin_status(request): #approve or reject
#     try:
#         data = json.loads(request.body)
#         superadmin_id = request.session.get('superadmin_id') or data.get("superadmin_id")

#         if not superadmin_id:
#             return JsonResponse({
#                 "error": "Unauthorized. Please login as Super Admin."
#             }, status=401)

#         superadmin = SuperAdmin.objects.filter(id=superadmin_id).first()
#         if not superadmin:
#             return JsonResponse({"error": "Invalid Super Admin session"}, status=401)

#         email = data.get("email")
#         approval = data.get("approval")

#         if not email or not approval:
#             return JsonResponse({"error": "Email and approval are required"}, status=400)

#         admin = Admin.objects.filter(email=email).first()
#         if not admin:
#             return JsonResponse({"error": "Admin not found"}, status=404)

#         # if admin.approval == 2:
#         #     return JsonResponse({"message": "Admin already approved"}, status=200)

#         # if admin.approval == 3:
#         #     return JsonResponse({"message": "Admin already rejected"}, status=200)

#         if approval == 2:
#             if admin.approval == 0:
#                 return JsonResponse({
#                     "error": "Only pending admins can be approved"
#                 }, status=400)

#             admin.approval = 2
#             admin.status = 1
#             message = "Admin approved successfully"

#         elif approval == 3:
#             admin.approval = 3
#             admin.status = 0
#             Pilot.objects.filter(admin=admin).update(status=0)

#             message = "Admin rejected and suspended successfully"

#         else:
#             return JsonResponse({"error": "Invalid approval value"}, status=400)

#         admin.save()
#         send_admin_status_email(admin, approval)
#         return JsonResponse({
#             "message": message
#         }, status=200)

#     except Exception as e:
#         return JsonResponse({"error": str(e)}, status=500)


# @csrf_exempt
# def update_admin_status(request): # approve, reject, suspend, activate
#     try:
#         data = json.loads(request.body)
#         superadmin_id = request.session.get('superadmin_id') or data.get("superadmin_id")

#         if not superadmin_id:
#             return JsonResponse({
#                 "error": "Unauthorized. Please login as Super Admin."
#             }, status=401)

#         superadmin = SuperAdmin.objects.filter(id=superadmin_id).first()
#         if not superadmin:
#             return JsonResponse({"error": "Invalid Super Admin session"}, status=401)

#         email = data.get("email")
#         action = data.get("action")  # 'approve', 'reject', 'suspend', 'activate'

#         if not email or not action:
#             return JsonResponse({"error": "Email and action are required"}, status=400)

#         admin = Admin.objects.filter(email=email).first()
#         if not admin:
#             return JsonResponse({"error": "Admin not found"}, status=404)

#         message = ""

#         if action == 'approve':
#             if admin.approval != 1:
#                 return JsonResponse({
#                     "error": "Only pending registrations can be approved"
#                 }, status=400)
#             admin.approval = 2
#             admin.status = 1
#             message = "Admin approved successfully"

#         elif action == 'reject':
#             if admin.approval != 1:
#                 return JsonResponse({
#                     "error": "Only pending registrations can be rejected"
#                 }, status=400)
#             admin.approval = 3
#             admin.status = 0
#             Pilot.objects.filter(admin=admin).update(status=0)
#             message = "Admin rejected and suspended successfully"

#         elif action == 'suspend':
#             if admin.approval != 2:
#                 return JsonResponse({
#                     "error": "Only approved admins can be suspended"
#                 }, status=400)
#             admin.approval = 3
#             admin.status = 0
#             Pilot.objects.filter(admin=admin).update(status=0)
#             message = "Admin suspended successfully"

#         elif action == 'activate':
#             if admin.approval != 3:
#                 return JsonResponse({
#                     "error": "Only suspended admins can be activated"
#                 }, status=400)
#             admin.approval = 2
#             admin.status = 1
#             message = "Admin activated successfully"

#         else:
#             return JsonResponse({"error": "Invalid action"}, status=400)

#         admin.save()
#         send_admin_status_email(admin, action)
#         return JsonResponse({
#             "message": message
#         }, status=200)

#     except Exception as e:
#         return JsonResponse({"error": str(e)}, status=500)

from django.views.decorators.csrf import csrf_exempt
from django.http import JsonResponse
import json
from django.utils import timezone

@csrf_exempt
def update_admin_status(request):  # approve, reject, suspend, activate
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        role = request.session.get("role")
        user_id = request.session.get("user_id")

        if not role or not user_id:
            return JsonResponse({"error": "Unauthorized. Please login."}, status=401)

        if role != "superadmin":
            return JsonResponse({"error": "Only superadmins can update admin status."}, status=403)

        superadmin = SuperAdmin.objects.filter(id=user_id).first()
        if not superadmin:
            return JsonResponse({"error": "Invalid Superadmin session"}, status=401)

        data = json.loads(request.body)
        email = data.get("email")
        action = data.get("action")

        if not email or not action:
            return JsonResponse({"error": "Email and action are required"}, status=400)

        admin = Admin.objects.filter(email=email).first()
        if not admin:
            return JsonResponse({"error": "Admin not found"}, status=404)

        message = ""

        from django.utils import timezone

        if action == 'approve':
            if admin.approval != 1:
                return JsonResponse({"error": "Only pending registrations can be approved"}, status=400)

            admin.approval = 2
            admin.status = 1

            admin.changed_on = None
            admin.changed_on = timezone.now()   # ✅ approve date

            message = "Admin approved successfully"

        elif action == 'reject':
            if admin.approval != 1:
                return JsonResponse({"error": "Only pending registrations can be rejected"}, status=400)

            admin.approval = 3
            admin.status = 0

            admin.changed_on = timezone.now()   # ✅ reject date
            # admin.changed_on = None

            Pilot.objects.filter(admin=admin).update(status=0)

            message = "Admin rejected and suspended successfully"

        elif action == 'suspend':
            if admin.approval != 2:
                return JsonResponse({"error": "Only approved admins can be suspended"}, status=400)

            admin.approval = 3
            admin.status = 0

            admin.changed_on = timezone.now()   # ✅ suspend date
            # admin.changed_on = None

            Pilot.objects.filter(admin=admin).update(status=0)

            message = "Admin suspended successfully"

        elif action == 'activate':
            if admin.approval != 3:
                return JsonResponse({"error": "Only suspended admins can be activated"}, status=400)

            admin.approval = 2
            admin.status = 1

            admin.changed_on = None
            admin.changed_on = timezone.now()   # ✅ activate date

            message = "Admin activated successfully"

        else:
            return JsonResponse({"error": "Invalid action"}, status=400)

        admin.save()
        send_admin_status_email(admin, action)

        return JsonResponse({"message": message}, status=200)

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

def send_admin_status_email(admin, approval):
    try:
        logo_url = f"{settings.AWS_S3_BUCKET_URL}/aviation-logo.png" if hasattr(settings, 'AWS_S3_BUCKET_URL') else ""

        if approval in ('approve', 'activate'):
            subject = "[Pavaman] Account Approved"

            text_content = f"""
Hello {admin.name},

Your admin account has been APPROVED.

You can now login to the system.

Regards,
Team
"""

            html_content = f"""
            <html>
            <head>
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap');

                    @media only screen and (max-width: 600px) {{
                        .container {{
                            width: 90% !important;
                            padding: 20px !important;
                        }}
                        .logo {{
                            max-width: 180px !important;
                        }}
                    }}

                    .status {{
                        display: inline-block;
                        padding: 10px 20px;
                        background-color: #e6f4ea;
                        color: #28a745;
                        border-radius: 8px;
                        font-weight: 600;
                        margin-top: 15px;
                    }}
                </style>
            </head>

            <body style="margin:0; padding:0; font-family:'Inter', sans-serif; background-color:#f5f5f5;">

                <div class="container" style="margin:40px auto; background:#ffffff; border-radius:20px; box-shadow:0 4px 20px rgba(0,0,0,0.1); padding:40px 30px; max-width:480px;">

                    <div style="text-align:center;">
                        <img src="{logo_url}" alt="Logo" class="logo" style="max-width:280px; margin-bottom:20px;" />
                    </div>

                    <p style="color:#555;">Hello <b>{admin.name}</b>,</p>

                    <p style="color:#555;">
                        Your admin account has been <b style="color:#28a745;">APPROVED</b>.
                        You can now login and start using the platform.
                    </p>

                    <div style="text-align:center;">
                        <span class="status">Approved Successfully</span>
                    </div>

                    <p style="margin-top:30px; font-size:13px; color:#888;">
                        Disclaimer: This is an automated email. Please do not reply.
                    </p>

                </div>
            </body>
            </html>
            """

        elif approval in ('reject', 'suspend'):
            subject = "[Pavaman] Account Rejected"

            text_content = f"""
Hello {admin.name},

Your admin account has been REJECTED.

Please contact support for more details.

Regards,
Team
"""

            html_content = f"""
            <html>
            <head>
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap');

                    .status {{
                        display: inline-block;
                        padding: 10px 20px;
                        background-color: #fdecea;
                        color: #dc3545;
                        border-radius: 8px;
                        font-weight: 600;
                        margin-top: 15px;
                    }}
                </style>
            </head>

            <body style="margin:0; padding:0; font-family:'Inter', sans-serif; background-color:#f5f5f5;">

                <div style="margin:40px auto; background:#ffffff; border-radius:20px; box-shadow:0 4px 20px rgba(0,0,0,0.1); padding:40px 30px; max-width:480px;">

                    <div style="text-align:center;">
                        <img src="{logo_url}" alt="Logo" style="max-width:280px; margin-bottom:20px;" />
                    </div>

                    <p style="color:#555;">Hello <b>{admin.name}</b>,</p>

                    <p style="color:#555;">
                        Your admin account has been <b style="color:#dc3545;">REJECTED</b>.
                        Please contact support for further assistance.
                    </p>

                    <div style="text-align:center;">
                        <span class="status">Rejected</span>
                    </div>

                    <p style="margin-top:30px; font-size:13px; color:#888;">
                        Disclaimer: This is an automated email. Please do not reply.
                    </p>

                </div>
            </body>
            </html>
            """

        else:
            return False

        email_message = EmailMultiAlternatives(
            subject,
            text_content,
            settings.DEFAULT_FROM_EMAIL,
            [admin.email]
        )
        email_message.attach_alternative(html_content, "text/html")
        email_message.send()

        return True

    except Exception as e:
        print("EMAIL ERROR:", e)
        print(traceback.format_exc())
        return False

# @csrf_exempt
# def add_admin(request):
#     if request.method == "POST":
#         try:
#             data = json.loads(request.body)
#             name = data.get('name')
#             email = data.get('email')
#             mobile_no = data.get('mobile_no')
#             password = data.get('password')
#             status = data.get('status', 1)
#             if not name or not email or not password:
#                 return JsonResponse({"error": "name, email, and password are required.", "status_code": 400}, status=400)
#             if Admin.objects.filter(name=name).exists():
#                 return JsonResponse({"error": "name already exists. Please choose a different name.", "status_code": 409}, status=409)
#             if Admin.objects.filter(email=email).exists():
#                 return JsonResponse({"error": "Email already exists. Please use a different email.", "status_code": 409}, status=409)
#             admin = Admin(name=name, email=email, password=password, status=int(status))
#             admin.save()
#             return JsonResponse({"message": "Admin added successfully", "id": admin.id, "status_code": 201}, status=201)
#         except json.JSONDecodeError:
#             return JsonResponse({"error": "Invalid JSON data in the request body.", "status_code": 400}, status=400)
#         except IntegrityError:
#             return JsonResponse({"error": "Database integrity error.", "status_code": 500}, status=500)
#         except Exception as e:
#             return JsonResponse({"error": f"An unexpected error occurred: {str(e)}", "status_code": 500}, status=500)
#     return JsonResponse({"error": "Invalid HTTP method. Only POST is allowed.", "status_code": 405}, status=405)


@csrf_exempt
def admin_password_login(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        data = json.loads(request.body or "{}")
        email = data.get("email", "").strip().lower()
        password = data.get("password", "").strip()

        if not email or not password:
            return JsonResponse({"error": "Email and password required."}, status=400)

        user = SuperAdmin.objects.filter(email=email).first()
        role = "superadmin"

        if user:
            if user.password != password:
                return JsonResponse({"error": "Invalid password"}, status=401)

        else:
            user = Admin.objects.filter(email=email,status=1).first()
            role = "admin"

            if not user:
                return JsonResponse({"error": "Email not found"}, status=404)

            if not check_password(password, user.password):
                return JsonResponse({"error": "Invalid password"}, status=401)

            if user.approval == 1:
                return JsonResponse({"error": "Account pending approval"}, status=403)

            elif user.approval == 3:
                return JsonResponse({"error": "Account rejected"}, status=403)

            elif user.approval != 2:
                return JsonResponse({"error": "Invalid account status"}, status=403)

        return JsonResponse({
            "message": "Password verified. Please choose OTP method (email/mobile).",
            "choose_otp_send_type": True,
            "email": user.email,
            "name":user.name,
            "role": role,
            "logo_url": generate_presigned_url(user.logo_path) if role == "admin" and user.logo_path else None
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def admin_send_otp(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        data = json.loads(request.body or "{}")
        email = data.get("email", "").strip().lower()
        otp_send_type = data.get("otp_send_type", "").strip().lower()

        if otp_send_type not in ["email", "mobile"]:
            return JsonResponse({"error": "otp_send_type must be email or mobile."}, status=400)

        user = SuperAdmin.objects.filter(email=email).first()
        role = "superadmin"

        if not user:
            user = Admin.objects.filter(email=email,status=1).first()
            role = "admin"

        if not user:
            return JsonResponse({"error": "Invalid email"}, status=404)

        otp = str(random.randint(100000, 999999))
        user.otp = otp
        user.otp_send_type = otp_send_type
        user.changed_on = timezone.now()
        user.save()

        if otp_send_type == "mobile":
            send_otp_sms(user.mobile_no, otp)
        else:
            send_otp_email(user.email, user.name, otp)

        return JsonResponse({
            "message": f"OTP sent to {otp_send_type}",
            "otp_send_type": otp_send_type,
            "email": email,
            "role": role
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

def send_otp_sms(mobile_no, otp):
    try:
        send_verify_mobile([mobile_no], otp)
        return True
    except Exception as e:
        return False


# @csrf_exempt
# def admin_verify_otp(request):
#     """Step 3: Verify OTP."""
#     if request.method != "POST":
#         return JsonResponse({"error": "Only POST allowed."}, status=405)

#     try:
#         data = json.loads(request.body or "{}")
#         email = data.get("email", "").strip().lower()
#         otp = data.get("otp", "").strip()
#         otp_send_type = data.get("otp_send_type", "").strip().lower()

#         user = SuperAdmin.objects.filter(email=email).first()
#         user_type = "superadmin"

#         if not user:
#             user = Admin.objects.filter(email=email,status=1).first()
#             user_type = "admin"

#         if not user:
#             return JsonResponse({"error": "Invalid email"}, status=404)

#         if not user:
#             return JsonResponse({"error": "Invalid email"}, status=404)

#         if otp_send_type != user.otp_send_type:
#             return JsonResponse({"error": "OTP type mismatch. Please resend OTP."}, status=401)

#         if str(user.otp) != otp:
#             return JsonResponse({"error": "Incorrect OTP."}, status=401)

#         if user.changed_on and timezone.now() > user.changed_on + timedelta(minutes=5):
#             return JsonResponse({"error": "OTP expired."}, status=401)

#         user.otp = None
#         user.otp_send_type = None
#         user.save()

#         request.session.flush()

#         if user_type == "superadmin":
#             request.session["superadmin_id"] = user.id
#         else:
#             request.session["admin_id"] = user.id
#         request.session.save()

#         return JsonResponse({
#             "message": "OTP verified. Login success.",
#             "user_id": user.id,
#             "role": user_type,
#             "name": user.name,
#             "email": user.email,
#             "session_key": request.session.session_key
#         })

#     except Exception as e:
#         return JsonResponse({"error": str(e)}, status=500)


@csrf_exempt
def admin_verify_otp(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed"}, status=405)

    try:
        data = json.loads(request.body)
        email = (data.get("email") or "").lower().strip()
        otp = (data.get("otp") or "").strip()

        if not email or not otp:
            return JsonResponse({"error": "Email and OTP are required"}, status=400)

        user = SuperAdmin.objects.filter(email=email).first()
        role = "superadmin"

        if not user:
            user = Admin.objects.filter(email=email, status=1).first()
            role = "admin"

        if not user:
            return JsonResponse({"error": "Invalid email"}, status=404)

        if str(user.otp) != otp:
            return JsonResponse({"error": "Invalid OTP"}, status=401)

         # ✅ ADD THIS (OTP expiry check - 2 minutes)
        if user.changed_on and timezone.now() > user.changed_on + timedelta(minutes=2):
            return JsonResponse({"error": "OTP expired"}, status=401)

        # clear session
        request.session.flush()

        # set session
        request.session["role"] = role
        request.session["user_id"] = user.id
        request.session["view_as_admin_id"] = None
        request.session.save()

        return JsonResponse({
            "message": "OTP verified. Login success.",
            "user_id": user.id,
            "role": role,
            "name": user.name,
            "email": user.email,
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

def get_current_context(request):
    role = request.session.get("role")
    user_id = request.session.get("user_id")
    view_as_admin_id = request.session.get("view_as_admin_id")

    # ✅ SuperAdmin switched to Admin
    if role == "superadmin" and view_as_admin_id:
        return {
            "type": "admin",
            "id": view_as_admin_id
        }

    # ✅ SuperAdmin normal view (own data)
    if role == "superadmin":
        return {
            "type": "superadmin",
            "id": user_id
        }

    # ✅ Admin normal login
    if role == "admin":
        return {
            "type": "admin",
            "id": user_id
        }

    return None

@csrf_exempt
def switch_to_admin(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed"}, status=405)

    if request.session.get("role") != "superadmin":
        return JsonResponse({"error": "Only superadmin allowed"}, status=403)

    try:
        data = json.loads(request.body)
        admin_id = data.get("admin_id")

        admin = Admin.objects.filter(id=admin_id).first()
        if not admin:
            return JsonResponse({"error": "Admin not found"}, status=404)

        # ✅ SWITCH CONTEXT
        request.session["view_as_admin_id"] = admin.id
        request.session.save()

        return JsonResponse({
            "message": f"Now viewing {admin.name} dashboard",
            "view_as_admin_id": admin.id
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def exit_admin_view(request):
    if request.session.get("role") != "superadmin":
        return JsonResponse({"error": "Unauthorized"}, status=403)

    request.session["view_as_admin_id"] = None
    request.session.save()

    return JsonResponse({"message": "Back to SuperAdmin dashboard"})


from django.contrib.auth import logout

@csrf_exempt
def admin_logout(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    if request.session.get("user_id"):
        logout(request)
        request.session.flush()

        return JsonResponse({
            "message": "Logout successful"
        }, status=200)

    return JsonResponse({
        "error": "No active session found"
    }, status=401)

@csrf_exempt
def admin_resend_otp(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        data = json.loads(request.body or "{}")
        email = data.get("email", "").strip().lower()
        otp_send_type = data.get("otp_send_type", "").strip().lower()

        if not email:
            return JsonResponse({"error": "Email is required."}, status=400)

        user = SuperAdmin.objects.filter(email=email).first()
        user_type = "superadmin"

        if not user:
            user = Admin.objects.filter(email=email,status=1).first()
            user_type = "admin"

        if not user:
            return JsonResponse({"error": "Invalid email"}, status=404)

        if not user.otp_send_type:
            if otp_send_type not in ["email", "mobile"]:
                return JsonResponse({
                    "error": "otp_send_type must be email or mobile."
                }, status=400)

            user.otp_send_type = otp_send_type

        otp = str(random.randint(100000, 999999))
        user.otp = otp
        user.changed_on = timezone.now()
        user.save()

        if user.otp_send_type == "mobile":
            if not send_otp_sms(user.mobile_no, otp):
                return JsonResponse({"error": "Failed to resend mobile OTP."}, status=500)
        else:
            if not send_otp_email(user.email, user.name, otp):
                return JsonResponse({"error": "Failed to resend email OTP."}, status=500)

        return JsonResponse({
            "message": f"OTP resent to {user.otp_send_type}.",
            "email": user.email,
            "otp_send_type": user.otp_send_type,
            "user_type": user_type
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

def send_otp_email(email, name, otp):
    subject = "[Pavaman] Please Verify Your Email"
    logo_url = f"{settings.AWS_S3_BUCKET_URL}/aviation-logo.png" if hasattr(settings, 'AWS_S3_BUCKET_URL') else ""

    text_content = f"""
    Hello {name},
    Your OTP is: {otp}
    """
    html_content = f"""
    <html>
    <head>
        <style>
            @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap');
            @media only screen and (max-width: 600px) {{
                .container {{
                    width: 90% !important;
                    padding: 20px !important;
                }}
                .logo {{
                    max-width: 180px !important;
                    height: auto !important;
                }}
            }}
        </style>
    </head>
    <body style="margin: 0; padding: 0; font-family: 'Inter', sans-serif; background-color: #f5f5f5;">
        <div class="container" style="margin: 40px auto; background-color: #ffffff; border-radius: 20px; box-shadow: 0 4px 20px rgba(0,0,0,0.1); padding: 40px 30px; max-width: 480px; text-align: left;">
            <div style="text-align: center;">
                <img src="{logo_url}" alt="Pavaman Logo" class="logo" style="max-width: 280px; height: auto; margin-bottom: 20px;" />
                <h2 style="margin-top: 0; color: #222;">Verify your email</h2>
            </div>
            <div style="margin-bottom: 10px; color: #555; font-size: 14px;">
                Hello {name},
            </div>

            <p style="color: #555; margin-bottom: 30px;">
                Please use the OTP below to verify your email.
            </p>

            <p class="otp" style="font-size: 28px; font-weight: bold; color: #4450A2; background: #f2f2f2; display: block; padding: 12px 24px; border-radius: 10px; letter-spacing: 4px; width: fit-content; margin: 0 auto;">
                {otp}
            </p>
            <p style="color: #888; font-size: 14px; margin-top: 20px;">
                If you didn't request this, you can safely ignore this email.<br/>
                You're receiving this because you have an account on Pavaman Aviation.
            </p>
            <p style="margin-top: 30px; font-size: 14px; color: #888;">Disclaimer: This is an automated email. Please do not reply.</p>
        </div>
    </body>
    </html>
    """
    try:
        email_message = EmailMultiAlternatives(
            subject, text_content, settings.DEFAULT_FROM_EMAIL, [email]
        )
        email_message.attach_alternative(html_content, "text/html")
        email_message.send()
        return True
    except Exception as e:
        print("EMAIL ERROR:", e)
        print(traceback.format_exc())
        return False

import boto3
from django.conf import settings

def generate_presigned_url(file_key):
    s3 = boto3.client(
        's3',
        aws_access_key_id=settings.AWS_ACCESS_KEY_ID,
        aws_secret_access_key=settings.AWS_SECRET_ACCESS_KEY,
        region_name=settings.AWS_S3_REGION_NAME
    )

    try:
        url = s3.generate_presigned_url(
            'get_object',
            Params={
                'Bucket': settings.AWS_STORAGE_BUCKET_NAME,
                'Key': file_key
            },
            ExpiresIn=3600  # 1 hour
        )
        return url
    except Exception:
        return None

@csrf_exempt
def view_all_admins(request):
    try:
        if request.method != "POST":
            return JsonResponse({"error": "Only POST allowed."}, status=405)

        data = json.loads(request.body or "{}")

        admins = Admin.objects.all().order_by("-id")

        admin_list = []
        for admin in admins:
            admin_list.append({
                "id": admin.id,
                "name": admin.name,
                "contact_name": admin.contact_name,
                "email": admin.email,
                "mobile_no": admin.mobile_no,
                "address": admin.address,

                "drones": admin.drones,
                "pilots": admin.pilots,

                "logo_url": generate_presigned_url(admin.logo_path) if admin.logo_path else None,
                "gst_url": generate_presigned_url(admin.gst_file_path) if admin.gst_file_path else None,

                "approval": admin.approval,
                "status": admin.status,

               "status_date": (
                admin.changed_on.strftime("%Y-%m-%d %H:%M:%S")
                if admin.status == 1 and admin.changed_on
                else (
                    admin.changed_on.strftime("%Y-%m-%d %H:%M:%S")
                    if admin.status == 0 and admin.changed_on
                    else None
                )
            ),


                "created_on": admin.created_on.strftime("%Y-%m-%d %H:%M:%S") if admin.created_on else None
            })

        return JsonResponse({
            "count": len(admin_list),
            "data": admin_list
        }, status=200)

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)


# @csrf_exempt
# def view_pilots(request):
#     if request.method != "POST":
#         return JsonResponse({"error": "Only POST allowed."}, status=405)
#     data=json.loads(request.body or "{}")
#     superadmin_id = request.session.get('superadmin_id') or data.get("superadmin_id")
#     admin_id = request.session.get("admin_id")

#     if not (admin_id or superadmin_id):
#         return JsonResponse({
#             "error": "Unauthorized. Please login."
#         }, status=401)
#     superadmin = SuperAdmin.objects.filter(id=superadmin_id).first()
#     if not superadmin:
#         return JsonResponse({"error": "Invalid Super Admin session"}, status=401)

#     admin = Admin.objects.filter(id=admin_id,status=1).first()
#     if not admin:
#         return JsonResponse({"error": "Invalid Admin session"}, status=401)

#     pilots = Pilot.objects.all()

#     pilot_list = []
#     for pilot in pilots:
#         device_list = []

#         devices = PilotDeviceAccess.objects.filter(pilot=pilot)

#         for device in devices:
#             device_list.append({
#                 "device_id": device.id,
#                 "ip_address": device.ip_address,
#                 "admin_approved": device.admin_approved,
#                 "is_active": device.is_active,
#                 "device_status":device.device_status,
#                 "changed_on": device.changed_on,
#                 "created_on": format_datetime_ist(device.created_on),
#             })
#         pilot_list.append({
#             "id": pilot.id,
#             "name": f"{pilot.first_name} {pilot.last_name}",
#             "email": pilot.email,
#             "mobile_no": pilot.mobile_no,
#             # "admin_approved": pilot.admin_approved,
#             "login_on": pilot.login_on,
#             "login_status": pilot.login_status,
#             "logout_status": pilot.logout_status,
#             "logout_on": pilot.logout_on,
#             "status":pilot.status,
#             # "device_id": pilot.device_id,
#             # "device_limit": pilot.device_limit,
#             "created_on": format_datetime_ist(pilot.created_on),
#             "devices": device_list
#         })

#     return JsonResponse({"pilots": pilot_list})

@csrf_exempt
def view_pilots(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        # ❌ Not logged in
        if not role or not user_id:
            return JsonResponse({"error": "Unauthorized. Please login."}, status=401)

        # ✅ Decide context
        if role == "superadmin":
            if view_as_admin_id:
                # 🔵 SuperAdmin viewing selected Admin
                pilots = Pilot.objects.filter(
                    admin_id=view_as_admin_id,

                )
                view_mode = "admin"
            else:
                # 🟢 SuperAdmin own company (ONLY is_superadmin_company=True)
                pilots = Pilot.objects.filter(
                    admin__superadmin_id=user_id,
                    admin__is_superadmin_company=True,

                )
                view_mode = "superadmin"

        elif role == "admin":
            # 🟠 Admin own data
            pilots = Pilot.objects.filter(
                admin_id=user_id,

            )
            view_mode = "admin"

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)

        pilot_list = []

        for pilot in pilots:
            devices = PilotDeviceAccess.objects.filter(pilot=pilot)

            device_list = [
                {
                    "device_id": device.id,
                    "ip_address": device.ip_address,
                    "admin_approved": device.admin_approved,
                    "is_active": device.is_active,
                    "device_status": device.device_status,
                    "changed_on": device.changed_on,
                    "created_on": format_datetime_ist(device.created_on),
                }
                for device in devices
            ]

            pilot_list.append({
                "id": pilot.id,
                "name": f"{pilot.first_name} {pilot.last_name}",
                "email": pilot.email,
                "mobile_no": pilot.mobile_no,
                "company_name": pilot.company_name,
                "admin_id": pilot.admin.id,
                "admin_name": pilot.admin.name,
                "is_superadmin_company": pilot.admin.is_superadmin_company,
                "login_on": pilot.login_on,
                "login_status": pilot.login_status,
                "logout_status": pilot.logout_status,
                "logout_on": pilot.logout_on,
                "status": pilot.status,
                "created_on": format_datetime_ist(pilot.created_on),
                "devices": device_list
            })

        return JsonResponse({
            "pilots": pilot_list,
            "view_mode": view_mode
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

# @csrf_exempt
# def view_pilots_and_vehicles(request):
#     if request.method != "POST":
#         return JsonResponse({"error": "Only POST allowed."}, status=405)

#     superadmin_id = request.session.get('superadmin_id')
#     admin_id = request.session.get("admin_id")

#     if not (admin_id or superadmin_id):
#             return JsonResponse({
#                 "error": "Unauthorized. Please login."
#             }, status=401)

#     if admin_id:
#         admin = Admin.objects.filter(id=admin_id,status=1).first()
#         if not admin:
#             return JsonResponse({"error": "Invalid Admin session"}, status=401)

#     if superadmin_id:
#         superadmin = SuperAdmin.objects.filter(id=superadmin_id).first()
#         if not superadmin:
#             return JsonResponse({"error": "Invalid SuperAdmin session"}, status=401)

#     pilots = Pilot.objects.all()

#     pilot_list = []

#     for pilot in pilots:
#         vehicles = Vehicle.objects.filter(pilot=pilot)

#         vehicle_list = []
#         for v in vehicles:
#             vehicle_list.append({
#                 "vehicle_id": v.vehicle_id,
#                 "vehicle_name": v.vehicle_name,
#                 "uin":v.uin,
#                 "registered_date":v.registered_date,
#                 "created_at":format_datetime_ist(v.created_at)
#             })

#         pilot_list.append({
#             "id": pilot.id,
#             "name": f"{pilot.first_name} {pilot.last_name}",
#             "email": pilot.email,
#             "mobile_no": pilot.mobile_no,
#             # "admin_approved": pilot.admin_approved,
#             "login_on": pilot.login_on,
#             "login_status": pilot.login_status,
#             "logout_status": pilot.logout_status,
#             "logout_on": pilot.logout_on,
#             "status":pilot.status,
#             # "device_id": pilot.device_id,
#             # "device_limit": pilot.device_limit,
#             "created_on": format_datetime_ist(pilot.created_on),
#             "vehicles": vehicle_list,
#         })

#     return JsonResponse({"pilots": pilot_list})

# @csrf_exempt
# def view_pilots_and_vehicles(request):
#     if request.method != "POST":
#         return JsonResponse({"error": "Only POST allowed."}, status=405)

#     try:
#         role = request.session.get("role")
#         user_id = request.session.get("user_id")
#         view_as_admin_id = request.session.get("view_as_admin_id")

#         # ❌ Not logged in
#         if not role or not user_id:
#             return JsonResponse({"error": "Unauthorized. Please login."}, status=401)

#         # ✅ Decide context (SAME AS view_pilots)
#         if role == "superadmin":
#             if view_as_admin_id:
#                 # 🔵 SuperAdmin viewing selected Admin
#                 pilots = Pilot.objects.filter(
#                     admin_id=view_as_admin_id,
#                     status=1
#                 )
#                 view_mode = "admin"
#             else:
#                 # 🟢 SuperAdmin own company only
#                 pilots = Pilot.objects.filter(
#                     admin__superadmin_id=user_id,
#                     admin__is_superadmin_company=True,
#                     status=1
#                 )
#                 view_mode = "superadmin"

#         elif role == "admin":
#             # 🟠 Admin own data
#             pilots = Pilot.objects.filter(
#                 admin_id=user_id,
#                 status=1
#             )
#             view_mode = "admin"

#         else:
#             return JsonResponse({"error": "Invalid role"}, status=403)

#         # 🚀 Optimization (VERY IMPORTANT)
#         pilots = pilots.select_related("admin").prefetch_related("vehicle_set")

#         pilot_list = []

#         for pilot in pilots:
#             vehicles = pilot.vehicle_set.all()

#             vehicle_list = [
#                 {
#                     "vehicle_id": v.vehicle_id,
#                     "vehicle_name": v.vehicle_name,
#                     "uin": v.uin,
#                     "registered_date": v.registered_date,
#                     "created_at": format_datetime_ist(v.created_at)
#                 }
#                 for v in vehicles
#             ]

#             pilot_list.append({
#                 "id": pilot.id,
#                 "name": f"{pilot.first_name} {pilot.last_name}",
#                 "email": pilot.email,
#                 "mobile_no": pilot.mobile_no,
#                 "company_name": pilot.company_name,
#                 "admin_id": pilot.admin.id,
#                 "admin_name": pilot.admin.name,
#                 "is_superadmin_company": pilot.admin.is_superadmin_company,
#                 "login_on": pilot.login_on,
#                 "login_status": pilot.login_status,
#                 "logout_status": pilot.logout_status,
#                 "logout_on": pilot.logout_on,
#                 "status": pilot.status,
#                 "created_on": format_datetime_ist(pilot.created_on),
#                 "vehicles": vehicle_list
#             })

#         return JsonResponse({
#             "pilots": pilot_list,
#             "view_mode": view_mode
#         })

#     except Exception as e:
#         return JsonResponse({"error": str(e)}, status=500)


@csrf_exempt
def view_pilots_and_vehicles(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        # ❌ Not logged in
        if not role or not user_id:
            return JsonResponse({"error": "Unauthorized. Please login."}, status=401)

        # ✅ Decide context (SAME AS view_pilots)
        if role == "superadmin":
            if view_as_admin_id:
                # 🔵 SuperAdmin viewing selected Admin
                pilots = Pilot.objects.filter(
                    admin_id=view_as_admin_id,
                    # status=1
                )
                view_mode = "admin"
            else:
                # 🟢 SuperAdmin own company only
                pilots = Pilot.objects.filter(
                    admin__superadmin_id=user_id,
                    admin__is_superadmin_company=True,
                    # status=1
                )
                view_mode = "superadmin"

        elif role == "admin":
            # 🟠 Admin own data
            pilots = Pilot.objects.filter(
                admin_id=user_id,
                # status=1
            )
            view_mode = "admin"

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)

        # 🚀 Optimization (VERY IMPORTANT)
        pilots = pilots.select_related("admin").prefetch_related("vehicle_set")

        pilot_list = []

        for pilot in pilots:
            vehicles = pilot.vehicle_set.all()

            vehicle_list = [
                {
                    "vehicle_id": v.vehicle_id,
                    "vehicle_name": v.vehicle_name,
                    "uin": v.uin,
                    "registered_date": v.registered_date,
                    "created_at": format_datetime_ist(v.created_at)
                }
                for v in vehicles
            ]

            pilot_list.append({
                "id": pilot.id,
                "name": f"{pilot.first_name} {pilot.last_name}",
                "email": pilot.email,
                "mobile_no": pilot.mobile_no,
                "company_name": pilot.company_name,
                "admin_id": pilot.admin.id,
                "admin_name": pilot.admin.name,
                "is_superadmin_company": pilot.admin.is_superadmin_company,
                "login_on": pilot.login_on,
                "login_status": pilot.login_status,
                "logout_status": pilot.logout_status,
                "logout_on": pilot.logout_on,
                "status": pilot.status,
                "created_on": format_datetime_ist(pilot.created_on),
                "vehicles": vehicle_list
            })

        return JsonResponse({
            "pilots": pilot_list,
            "view_mode": view_mode
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

#suspend or active pilot
# @csrf_exempt
# def update_pilot_status(request):
#     if request.method != "POST":
#         return JsonResponse({"error": "Only POST allowed"}, status=405)

#     try:
#         data = json.loads(request.body.decode('utf-8'))
#         admin_id = request.session.get("admin_id")
#         superadmin_id = request.session.get("superadmin_id")

#         if not (admin_id or superadmin_id):
#             return JsonResponse({
#                 "error": "Unauthorized. Please login."
#             }, status=401)

#         if admin_id:
#             admin = Admin.objects.filter(id=admin_id,status=1).first()
#             if not admin:
#                 return JsonResponse({"error": "Invalid Admin session"}, status=401)

#         if superadmin_id:
#             superadmin = SuperAdmin.objects.filter(id=superadmin_id).first()
#             if not superadmin:
#                 return JsonResponse({"error": "Invalid SuperAdmin session"}, status=401)

#         pilot_email = data.get("pilot_email")
#         status = data.get("status")  # 1 = active, 0 = suspend

#         if not pilot_email or status not in [0, 1]:
#             return JsonResponse({
#                 "error": "pilot_email and valid status (0 or 1) required"
#             }, status=400)

#         pilot = Pilot.objects.filter(email=pilot_email).first()
#         if not pilot:
#             return JsonResponse({"error": "Pilot not found"}, status=404)

#         pilot.status = status
#         pilot.save()

#         return JsonResponse({
#             "message": "Pilot status updated",
#             "status": pilot.status
#         }, status=200)

#     except Exception as e:
#         return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def update_pilot_status(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed"}, status=405)

    try:
        data = json.loads(request.body.decode('utf-8'))

        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        if not role or not user_id:
            return JsonResponse({
                "error": "Unauthorized. Please login."
            }, status=401)

        pilot_email = data.get("pilot_email")
        status = data.get("status")  # 1 = active, 0 = suspend

        if not pilot_email or status not in [0, 1]:
            return JsonResponse({
                "error": "pilot_email and valid status (0 or 1) required"
            }, status=400)

        # 🔍 Get pilot with admin relation
        pilot = Pilot.objects.select_related("admin").filter(email=pilot_email).first()
        if not pilot:
            return JsonResponse({"error": "Pilot not found"}, status=404)

        # ==============================
        # ✅ ROLE-BASED ACCESS CONTROL
        # ==============================

        if role == "admin":
            # 🟠 Admin → only own pilots
            if pilot.admin_id != user_id:
                return JsonResponse({
                    "error": "You can only update your own pilots"
                }, status=403)

        elif role == "superadmin":

            # 🔴 BLOCK action if switched admin
            if view_as_admin_id:
                return JsonResponse({
                    "error": "Action not allowed in switched admin view (view-only mode)"
                }, status=403)

            # 🟢 SuperAdmin → only own company pilots
            if not (
                pilot.admin.superadmin_id == user_id and
                pilot.admin.is_superadmin_company is True
            ):
                return JsonResponse({
                    "error": "You can only update your own company pilots"
                }, status=403)

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)

        # ==============================
        # ✅ UPDATE STATUS
        # ==============================
        if pilot.status == status:
            return JsonResponse({
                "message": "Pilot already in requested status"
            }, status=200)

        pilot.status = status
        pilot.save()

        return JsonResponse({
            "message": "Pilot status updated successfully",
            "pilot_email": pilot.email,
            "status": pilot.status
        }, status=200)

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def registration_approved_rejected(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    data = json.loads(request.body)
    role = request.session.get("role")
    user_id = request.session.get("user_id")
    view_as_admin_id = request.session.get("view_as_admin_id")

    if not role or not user_id:
        return JsonResponse({"error": "Unauthorized Login"}, status=401)

    try:
        pilot_email = data.get('pilot_email')
        admin_approved = data.get('admin_approved')

        if not pilot_email or admin_approved is None:
            return JsonResponse(
                {"error": "pilot_email and admin_approved is required.", "status_code": 400},
                status=400
            )

        pilot = Pilot.objects.filter(email=pilot_email,register_status=1).first()
        if not pilot:
            return JsonResponse({"error": "Pilot not found or not verified.", "status_code": 404}, status=404)
         # ==============================
        # ✅ ROLE-BASED ACCESS CONTROL
        # ==============================

        if role == "admin":
            # 🟠 Admin → only own pilots
            if pilot.admin_id != user_id:
                return JsonResponse({
                    "error": "You can only manage your own pilots"
                }, status=403)

        elif role == "superadmin":

            # 🔴 BLOCK if switched mode
            if view_as_admin_id:
                return JsonResponse({
                    "error": "Action not allowed in switched admin view (view-only mode)"
                }, status=403)

            # 🟢 Only own company
            if not (
                pilot.admin.superadmin_id == user_id and
                pilot.admin.is_superadmin_company is True
            ):
                return JsonResponse({
                    "error": "You can only manage your company pilots"
                }, status=403)

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)


        device_request = PilotDeviceAccess.objects.filter(
            pilot=pilot,
            admin_id=pilot.admin_id,
            admin_approved__in=[0,1]
        ).order_by("-created_on").first()

        if not device_request:
            return JsonResponse({"error": "No pending device request"}, status=404)

        if admin_approved == 1:
            # 🔁 deactivate old devices
            # PilotDeviceAccess.objects.filter(
            #     pilot=pilot,
            #     is_active=True
            # ).update(is_active=False)

            device_request.admin_approved = 1
            device_request.is_active = True
            # device_request.otp = None
            device_request.save()

            return JsonResponse({"message": "Device approved"}, status=200)

        elif admin_approved == 2:
            device_request.admin_approved = 2
            device_request.is_active = False
            device_request.save()

            return JsonResponse({"message": "Device rejected"}, status=200)

        return JsonResponse({"error": "Invalid value"}, status=400)
        # ✅ ONLY ADDING LOGIC (no condition removed)
        # if admin_approved == 1:
        #     pilot.admin_approved = 1
        #     pilot.save()
        #     send_pilot_approval_email(pilot)
        #     return JsonResponse(
        #         {"message": "Pilot registration approved successfully", "status_code": 200},
        #         status=200
        #     )

        # elif admin_approved == 2:
        #     pilot.admin_approved = 2
        #     pilot.save()
        #     return JsonResponse(
        #         {"message": "Pilot registration rejected successfully", "status_code": 200},
        #         status=200
        #     )

        # else:
        #     return JsonResponse(
        #         {"error": "Invalid admin_approved value. Use 1 (approve) or 2 (reject)."},
        #         status=400
        #     )

    except json.JSONDecodeError:
        return JsonResponse(
            {"error": "Invalid JSON data in the request body.", "status_code": 400},
            status=400
        )

def send_pilot_approval_email(pilot):
    subject = "[Pavaman] Pilot Registration Approved"
    logo_url = f"{settings.AWS_S3_BUCKET_URL}/aviation-logo.png"

    text_content = f"""
    Hello {pilot.first_name},

    Congratulations!
    Your pilot registration has been approved.
    You can now log in and start using the Pavaman Aviation platform.
    """

    html_content = f"""
    <html>
    <head>
        <style>
            @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap');
            @media only screen and (max-width: 600px) {{
                .container {{
                    width: 90% !important;
                    padding: 20px !important;
                }}
                .logo {{
                    max-width: 180px !important;
                    height: auto !important;
                }}
            }}
        </style>
    </head>
    <body style="margin: 0; padding: 0; font-family: 'Inter', sans-serif; background-color: #f5f5f5;">
        <div class="container" style="margin: 40px auto; background-color: #ffffff; border-radius: 20px; box-shadow: 0 4px 20px rgba(0,0,0,0.1); padding: 40px 30px; max-width: 480px; text-align: left;">

            <div style="text-align: center;">
                <img src="{logo_url}" alt="Pavaman Logo" class="logo" style="max-width: 280px; height: auto; margin-bottom: 20px;" />
                <h2 style="margin-top: 0; color: #222;">Registration Approved</h2>
            </div>

            <div style="margin-bottom: 10px; color: #555; font-size: 14px;">
                Hello {pilot.first_name},
            </div>

            <p style="color: #555; margin-bottom: 20px;">
                🎉 Congratulations! Your <b>Pilot registration</b> has been successfully approved by the admin.
            </p>

            <p style="color: #555; margin-bottom: 30px;">
                You can now log in to your account and start using the Pavaman Aviation platform.
            </p>

            <div style="background: #f2f2f2; padding: 16px; border-radius: 10px; text-align: center; font-weight: 600; color: #4450A2;">
                Status: Approved
            </div>

            <p style="color: #888; font-size: 14px; margin-top: 20px;">
                If you have any questions, please contact our support team.<br/>
                You're receiving this email because you registered as a pilot on Pavaman Aviation.
            </p>

            <p style="margin-top: 30px; font-size: 14px; color: #888;">
                Disclaimer: This is an automated email. Please do not reply.
            </p>
        </div>
    </body>
    </html>
    """

    email_message = EmailMultiAlternatives(
        subject=subject,
        body=text_content,
        from_email=settings.DEFAULT_FROM_EMAIL,
        to=[pilot.email]
    )
    email_message.attach_alternative(html_content, "text/html")
    email_message.send()

@csrf_exempt
def pilot_details(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed"}, status=405)

    try:
        data = json.loads(request.body)

        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        # ❌ Not logged in
        if not role or not user_id:
            return JsonResponse({
                "error": "Unauthorized. Please login."
            }, status=401)

        # ==============================
        # ✅ DECIDE DATA SCOPE
        # ==============================

        if role == "superadmin":
            if view_as_admin_id:
                # 🔵 SuperAdmin viewing selected admin (view-only but stats allowed)
                pilots = Pilot.objects.filter(admin_id=view_as_admin_id)
                view_mode = "admin"

            else:
                # 🟢 SuperAdmin own company only
                pilots = Pilot.objects.filter(
                    admin__superadmin_id=user_id,
                    admin__is_superadmin_company=True
                )
                view_mode = "superadmin"

        elif role == "admin":
            # 🟠 Admin own pilots
            pilots = Pilot.objects.filter(admin_id=user_id)
            view_mode = "admin"

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)

        total_pilots = pilots.count()
        approved_pilots = pilots.filter(admin_approved=1).count()
        rejected_pilots = pilots.filter(admin_approved=2).count()
        pending_pilots = pilots.filter(admin_approved=0).count()

        active_pilots = pilots.filter(status=1).count()
        inactive_pilots = pilots.filter(status=0).count()

        logged_in_pilots = pilots.filter(login_status=1).count()
        logged_out_pilots = pilots.filter(logout_status=1).count()

        today_pilots = pilots.filter(
            created_on__date=now().date()
        ).count()

        return JsonResponse({
            "status_code": 200,
            "view_mode": view_mode,
            "pilot_statistics": {
                "total_pilots": total_pilots,
                "approved_pilots": approved_pilots,
                "rejected_pilots": rejected_pilots,
                "pending_pilots": pending_pilots,
                "active_pilots": active_pilots,
                "inactive_pilots": inactive_pilots,
                "logged_in_pilots": logged_in_pilots,
                "logged_out_pilots": logged_out_pilots,
                "today_registered_pilots": today_pilots
            }
        }, status=200)

    except json.JSONDecodeError:
        return JsonResponse(
            {"error": "Invalid JSON format"},
            status=400
        )

@csrf_exempt
def update_vehicle_uin(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST method allowed"}, status=405)

    try:
        data = json.loads(request.body)
        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        # ❌ Not logged in
        if not role or not user_id:
            return JsonResponse({
                "error": "Unauthorized. Please login."
            }, status=401)

        vehicle_id = data.get("vehicle_id")
        uin = data.get("uin")
        registered_date = data.get("registered_date")

        if not all([vehicle_id, uin, registered_date]):
            return JsonResponse(
                {"error": "vehicle_id, uin and registered_date are required"},
                status=400
            )

        # vehicle = Vehicle.objects.filter(vehicle_id=vehicle_id, admin=admin).first()
        vehicle = Vehicle.objects.select_related("admin").filter(
            vehicle_id=vehicle_id
        ).first()
        if not vehicle:
            return JsonResponse({"error": "Vehicle not found"}, status=404)

        # ✅ ROLE-BASED ACCESS CONTROL

        if role == "admin":
            # 🟠 Admin → only own vehicles
            if vehicle.admin_id != user_id:
                return JsonResponse({
                    "error": "You can only update your own vehicles"
                }, status=403)

        elif role == "superadmin":

            # 🔴 BLOCK in switched mode
            if view_as_admin_id:
                return JsonResponse({
                    "error": "Action not allowed in switched admin view (view-only mode)"
                }, status=403)

            # 🟢 Only own company vehicles
            if not (
                vehicle.admin.superadmin_id == user_id and
                vehicle.admin.is_superadmin_company is True
            ):
                return JsonResponse({
                    "error": "You can only update your company vehicles"
                }, status=403)

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)

        # Prevent duplicate UIN
        if Vehicle.objects.filter(uin=uin).exclude(vehicle_id=vehicle_id).exists():
            return JsonResponse(
                {"error": "UIN already assigned to another vehicle"},
                status=409
            )

        try:
            registered_date = datetime.strptime(
                registered_date, "%Y-%m-%d"
            ).date()
        except ValueError:
            return JsonResponse(
                {"error": "Invalid registered_date format (YYYY-MM-DD)"},
                status=400
            )

        vehicle.uin = uin
        vehicle.registered_date = registered_date
        vehicle.save(update_fields=["uin", "registered_date"])

        return JsonResponse({
            "message": "Vehicle UIN and registration date updated successfully",
            "vehicle_id": vehicle.vehicle_id,
            "uin": vehicle.uin,
            "registered_date": vehicle.registered_date,
            "status_code": 200
        }, status=200)

    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON format"}, status=400)
    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)


@csrf_exempt
def device_notifications(request):
    if request.method != "POST":
        return JsonResponse({"error": "POST only"}, status=405)

    data = json.loads(request.body)
    role = request.session.get("role")
    user_id = request.session.get("user_id")
    view_as_admin_id = request.session.get("view_as_admin_id")

    # ❌ Not logged in
    if not role or not user_id:
        return JsonResponse({
            "error": "Unauthorized. Please login."
        }, status=401)
    if role == "superadmin":
        if view_as_admin_id:
            # 🔵 SuperAdmin viewing selected admin
            base_queryset = PilotDeviceAccess.objects.filter(
                admin_id=view_as_admin_id
            )
            view_mode = "admin"
        else:
            # 🟢 SuperAdmin own company
            base_queryset = PilotDeviceAccess.objects.filter(
                admin__superadmin_id=user_id,
                admin__is_superadmin_company=True
            )
            view_mode = "superadmin"

    elif role == "admin":
        # 🟠 Admin own notifications
        base_queryset = PilotDeviceAccess.objects.filter(
            admin_id=user_id
        )
        view_mode = "admin"

    else:
        return JsonResponse({"error": "Invalid role"}, status=403)


    # Bell Count (only unread + pending)
    # unread_count = PilotDeviceAccess.objects.filter(
    #     admin_id=admin_id,
    #     admin_approved=0,
    #     is_active=False,
    #     is_read=False
    # ).count()
    unread_count = base_queryset.filter(
            admin_approved=0,
            is_active=False,
            is_read=False
        ).count()
    # Notification List (ONLY pending)
    notifications = base_queryset.filter(
        # admin_id=admin_id,
        admin_approved=0    #Approved / Rejected automatically disappear
    ).select_related("pilot").order_by("-created_on")[:20]

    data = []
    unread_ids = []

    for n in notifications:
        data.append({
            "id": n.id,
            "title": "New Device Login Attempt",
            "pilot_name": f"{n.pilot.first_name} {n.pilot.last_name}",
            "email": n.pilot.email,
            "mobile": n.pilot.mobile_no,
            "ip_address": n.ip_address,
            "is_read": n.is_read,
            "created_on": format_datetime_ist(n.created_on)
        })

        if not n.is_read:
            unread_ids.append(n.id)

    # Auto mark as read after fetch
    if unread_ids:
        PilotDeviceAccess.objects.filter(id__in=unread_ids).update(is_read=True)

    return JsonResponse({
        "view_mode": view_mode,
        "unread_count": unread_count,
        "notifications": data
    }, status=200)

#-----------------------------------------------------------
#drone data
@csrf_exempt
def view_missions(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        data = json.loads(request.body.decode("utf-8"))
        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        # ❌ Not logged in
        if not role or not user_id:
            return JsonResponse({
                "error": "Unauthorized. Please login."
            }, status=401)

        # ==============================
        # ✅ ROLE-BASED QUERYSET
        # ==============================

        if role == "superadmin":
            if view_as_admin_id:
                # 🔵 SuperAdmin viewing selected admin
                missions = Mission.objects.filter(
                    admin_id=view_as_admin_id
                )
                view_mode = "admin"
            else:
                # 🟢 SuperAdmin own company
                missions = Mission.objects.filter(
                    admin__superadmin_id=user_id,
                    admin__is_superadmin_company=True
                )
                view_mode = "superadmin"

        elif role == "admin":
            # 🟠 Admin own missions
            missions = Mission.objects.filter(
                admin_id=user_id
            )
            view_mode = "admin"

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)

        vehicle_map = {}

        for mission in missions:
            vehicle_id = mission.vehicle_id  # ✅ direct DB value

            if vehicle_id not in vehicle_map:
                vehicle_map[vehicle_id] = {
                    "vehicle_id": vehicle_id,
                    "admin_id": mission.admin_id,
                    "pilot_id": mission.pilot_id,
                    "missions": []
                }

            vehicle_map[vehicle_id]["missions"].append({
                "mission_id": str(mission.mission_id),
                "status": mission.status,
                "paused_at": mission.paused_at,
                "resumed_at": mission.resumed_at,
                "end_time": format_datetime_ist(mission.end_time),
                "start_time": format_datetime_ist(mission.start_time),

            })

        return JsonResponse(
            {"view_mode": view_mode,
            "vehicles": list(vehicle_map.values())},
            status=200
        )
    except Exception as e:
       return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def view_all_missions(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        data = json.loads(request.body.decode("utf-8"))

        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        # ❌ Not logged in
        if not role or not user_id:
            return JsonResponse({
                "error": "Unauthorized. Please login."
            }, status=401)

        # ==============================
        # ✅ ROLE-BASED QUERYSET
        # ==============================

        if role == "superadmin":
            if view_as_admin_id:
                # 🔵 SuperAdmin viewing selected admin
                missions = Mission.objects.filter(
                    admin_id=view_as_admin_id
                ).order_by("-start_time")

                view_mode = "admin"

            else:
                # 🟢 SuperAdmin own company only
                missions = Mission.objects.filter(
                    admin__superadmin_id=user_id,
                    admin__is_superadmin_company=True
                ).order_by("-start_time")

                view_mode = "superadmin"

        elif role == "admin":
            # 🟠 Admin own missions only
            missions = Mission.objects.filter(
                admin_id=user_id
            ).order_by("-start_time")

            view_mode = "admin"

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)
        # # 🔥 MISSION-WISE + GLOBAL ORDER
        # missions = (
        #     Mission.objects
        #     .filter(admin_id=admin_id)
        #     .order_by("-start_time")   # latest first
        # )

        mission_list = []

        for mission in missions:
            mission_list.append({
                "mission_id": str(mission.mission_id),
                "vehicle_id": mission.vehicle_id,
                "pilot_id": mission.pilot_id,
                "pilot_email":mission.pilot.email if mission.pilot else None,
                "pilot_name":mission.pilot.first_name + " " + mission.pilot.last_name if mission.pilot else None,
                "flight_mode": mission.flight_mode,
                "mission_type":mission.mission_type,
                "status": mission.get_status_display(),
                "plot_name": mission.plot_name,
                "grid_setup_source": mission.grid_setup_source,
                "start_time": format_datetime_ist(mission.start_time),
                "end_time": format_datetime_ist(mission.end_time),
                "paused_at": format_datetime_ist(mission.paused_at) if mission.paused_at else None,
                "resumed_at": format_datetime_ist(mission.resumed_at) if mission.resumed_at else None,
            })

        return JsonResponse({
            "view_mode": view_mode,
            "missions": mission_list
        }, status=200)

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

from collections import defaultdict
from .models import MissionEvent

@csrf_exempt
def get_mission_events(request):
    if request.method != "POST":
        return JsonResponse(
            {"error": "Only POST method allowed", "status_code": 405},
            status=405
        )
    try:
        data = json.loads(request.body.decode("utf-8"))

        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        # ❌ Not logged in
        if not role or not user_id:
            return JsonResponse({
                "error": "Unauthorized, session expired or not logged in"
            }, status=401)



        if role == "superadmin":
            if view_as_admin_id:
                events = MissionEvent.objects.filter(
                    admin_id=view_as_admin_id
                )
                view_mode = "admin"

            else:
                events = MissionEvent.objects.filter(
                    admin__superadmin_id=user_id,
                    admin__is_superadmin_company=True
                )
                view_mode = "superadmin"

        elif role == "admin":
            events = MissionEvent.objects.filter(
                admin_id=user_id
            )
            view_mode = "admin"

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)


        events = (
            events
            .select_related("mission", "vehicle", "pilot", "admin")
            .order_by("vehicle_id", "-created_at")
        )
    # events = (
    #     MissionEvent.objects
    #     .select_related("mission", "vehicle", "pilot", "admin")
    #     .filter(admin_id=admin_id)
    #     .order_by("vehicle_id", "-created_at")
    # )

        vehicle_wise_events = defaultdict(list)

        for event in events:
            vehicle_wise_events[event.vehicle_id].append({
                "event_id": event.id,
                "event_type": event.event_type,
                "event_status": event.event_status,
                "event_description": event.event_description,
                "created_at": event.created_at,

                "mission": {
                    "mission_id": event.mission_id,
                    "mission_name": getattr(event.mission, "mission_name", None),
                },

                "pilot_id": event.pilot_id,
            })

        response = []
        for vehicle_id, events_list in vehicle_wise_events.items():
            response.append({
                "vehicle_id": vehicle_id,
                "events": events_list,
                "count": len(events_list)
            })

        return JsonResponse(
            {
                "status": "success",
                "view_mode": view_mode,
                "total_vehicles": len(response),
                "data": response
            },
            status=200
        )

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)


# from collections import defaultdict
# from django.db.models import OuterRef, Subquery
# @csrf_exempt
# def drone_flying_details(request):
#     if request.method != "POST":
#         return JsonResponse(
#             {"error": "Only POST method allowed", "status_code": 405},
#             status=405
#         )

#     try:
#         data = json.loads(request.body.decode("utf-8"))
#         admin_id = request.session.get('admin_id')
#         if not admin_id:
#             return JsonResponse({"error": "Unauthorized, session expired or not logged in"}, status=401)

#         latest_hdop_subquery = TelemetryGPS.objects.filter(
#             mission_id=OuterRef("mission_id")
#         ).order_by("-ts").values("hdop")[:1]

#         telemetry_qs = TelemetryAttitude.objects.filter(
#             admin_id=admin_id
#         ).annotate(
#             hdop=Subquery(latest_hdop_subquery)
#         ).order_by("vehicle_id", "-ts")
#         # telemetry_qs = TelemetryAttitude.objects.filter(
#         #     admin_id=admin_id
#         # ).order_by("vehicle_id", "-ts")

#         vehicle_wise_data = defaultdict(list)

#         for telemetry in telemetry_qs:
#             vehicle_wise_data[telemetry.vehicle_id].append({
#                 "id": telemetry.id,
#                 "pilot_id": telemetry.pilot_id,
#                 "roll": telemetry.roll,
#                 "pitch": telemetry.pitch,
#                 "yaw": telemetry.yaw,
#                 "time_and_date": format_datetime_ist(telemetry.ts),
#                 "mission_id": telemetry.mission_id,
#                 "hdop": telemetry.hdop,
#             })

#         response = []
#         for vehicle_id, telemetry_list in vehicle_wise_data.items():
#             response.append({
#                 "vehicle_id": vehicle_id,
#                 "telemetry_data": telemetry_list,
#                 "count": len(telemetry_list)
#             })

#         return JsonResponse(
#             {
#                 "admin_id": admin_id,
#                 "vehicles": response
#             },
#             status=200
#         )

#     except json.JSONDecodeError:
#         return JsonResponse(
#             {"error": "Invalid JSON", "status_code": 400},
#             status=400
#         )
#     except Exception as e:
#         return JsonResponse(
#             {"error": str(e), "status_code": 500},
#             status=500
#         )
# @csrf_exempt
# def drone_spray_area_plot(request):
#     if request.method != "POST":
#         return JsonResponse(
#             {"error": "Only POST method allowed", "status_code": 405},
#             status=405
#         )

#     try:
#         data = json.loads(request.body.decode("utf-8"))
#         admin_id = request.session.get('admin_id')
#         if not admin_id:
#             return JsonResponse({"error": "Unauthorized, session expired or not logged in"}, status=401)

#         spray=TelemetrySpray.objects.filter(
#             admin_id=admin_id).order_by("vehicle_id","-ts")
#         spray_data = defaultdict(list)

#         for telemetry in spray:
#             spray_data[telemetry.vehicle_id].append({
#                 "id": telemetry.id,
#                 "pilot_id": telemetry.pilot_id,
#                 "spray_on": telemetry.spray_on,
#                 "spray_rate_lpm": telemetry.spray_rate_lpm,
#                 "flow_pulse": telemetry.flow_pulse,
#                 "tank_level_liters": telemetry.tank_level_liters,
#                 "mission_id": telemetry.mission_id,
#                 "time_and_date": format_datetime_ist(telemetry.ts),
#             })

#         response = []
#         for vehicle_id, telemetry_list in spray_data.items():
#             response.append({
#                 "vehicle_id": vehicle_id,
#                 "telemetry_data": telemetry_list,
#                 "count": len(telemetry_list)
#             })

#         return JsonResponse(
#             {
#                 "admin_id": admin_id,
#                 "vehicles": response
#             },
#             status=200
#         )
#     except json.JSONDecodeError:
#         return JsonResponse(
#             {"error": "Invalid JSON", "status_code": 400},
#             status=400
#         )
#     except Exception as e:
#         return JsonResponse(
#             {"error": str(e), "status_code": 500},
#             status=500
#         )

# @csrf_exempt
# def drone_battery_status(request):
#     if request.method != "POST":
#         return JsonResponse(
#             {"error": "Only POST method allowed", "status_code": 405},
#             status=405
#         )

#     try:
#         data = json.loads(request.body.decode("utf-8"))
#         admin_id = request.session.get('admin_id')
#         if not admin_id:
#             return JsonResponse({"error": "Unauthorized, session expired or not logged in"}, status=401)

#         battery_qs=TelemetryBattery.objects.filter(
#             admin_id=admin_id).order_by("vehicle_id","-ts")
#         battery_data = defaultdict(list)

#         for telemetry in battery_qs:
#             battery_data[telemetry.vehicle_id].append({
#                 "id": telemetry.id,
#                 "pilot_id": telemetry.pilot_id,
#                 "voltage": telemetry.voltage,
#                 "current": telemetry.current,
#                 "voltage": telemetry.voltage,
#                 "remaining": telemetry.remaining,
#                 "mission_id": telemetry.mission_id,
#                 "time_and_date": format_datetime_ist(telemetry.ts),
#             })

#         response = []
#         for vehicle_id, telemetry_list in battery_data.items():
#             response.append({
#                 "vehicle_id": vehicle_id,
#                 "telemetry_data": telemetry_list,
#                 "count": len(telemetry_list)
#             })

#         return JsonResponse(
#             {
#                 "admin_id": admin_id,
#                 "vehicles": response
#             },
#             status=200
#         )

#     except json.JSONDecodeError:
#         return JsonResponse(
#             {"error": "Invalid JSON", "status_code": 400},
#             status=400
#         )
#     except Exception as e:
#         return JsonResponse(
#             {"error": str(e), "status_code": 500},
#             status=500
#         )
# @csrf_exempt
# def drone_position(request):
#     if request.method != "POST":
#         return JsonResponse(
#             {"error": "Only POST method allowed", "status_code": 405},
#             status=405
#         )

#     try:
#         data = json.loads(request.body.decode("utf-8"))
#         admin_id = request.session.get('admin_id')
#         if not admin_id:
#             return JsonResponse({"error": "Unauthorized, session expired or not logged in"}, status=401)

#         position_qs=TelemetryPosition.objects.filter(
#             admin_id=admin_id).order_by("vehicle_id","-ts")
#         position_data = defaultdict(list)

#         for telemetry in position_qs:
#             position_data[telemetry.vehicle_id].append({
#                 "id": telemetry.id,
#                 "pilot_id": telemetry.pilot_id,
#                 "latitude": telemetry.lat,
#                 "longitude": telemetry.lng,
#                 "altitude": telemetry.alt,
#                 "remaining": telemetry.speed,
#                 "mission_id": telemetry.mission_id,
#                 "time_and_date":format_datetime_ist(telemetry.ts),
#             })

#         response = []
#         for vehicle_id, telemetry_list in position_data.items():
#             response.append({
#                 "vehicle_id": vehicle_id,
#                 "telemetry_data": telemetry_list,
#                 "count": len(telemetry_list)
#             })

#         return JsonResponse(
#             {
#                 "admin_id": admin_id,
#                 "vehicles": response
#             },
#             status=200
#         )

#     except json.JSONDecodeError:
#         return JsonResponse(
#             {"error": "Invalid JSON", "status_code": 400},
#             status=400
#         )
#     except Exception as e:
#         return JsonResponse(
#             {"error": str(e), "status_code": 500},
#             status=500
#         )


# @csrf_exempt
# def drone_flight_status_details(request):
#     if request.method != "POST":
#         return JsonResponse(
#             {"error": "Only POST method allowed", "status_code": 405},
#             status=405
#         )

#     try:
#         data = json.loads(request.body.decode("utf-8"))
#         admin_id = request.session.get('admin_id')
#         if not admin_id:
#             return JsonResponse({"error": "Unauthorized, session expired or not logged in"}, status=401)

#         status_qs = TelemetryStatus.objects.filter(
#             admin_id=admin_id
#         ).select_related(
#             "vehicle", "pilot", "mission"
#         ).order_by("vehicle_id", "-ts")

#         vehicle_wise_data = defaultdict(list)

#         for status in status_qs:
#             vehicle_wise_data[status.vehicle_id].append({
#                 "id": status.id,
#                 "pilot_id": status.pilot_id,
#                 "mission_id": status.mission_id,
#                 "flight_mode": status.flight_mode,
#                 "armed": status.armed,
#                 "failsafe": status.failsafe,
#                 "time_and_date": format_datetime_ist(status.ts),
#             })

#         response = []
#         for vehicle_id, status_list in vehicle_wise_data.items():
#             response.append({
#                 "vehicle_id": vehicle_id,
#                 "status_data": status_list,
#                 "count": len(status_list)
#             })

#         return JsonResponse(
#             {
#                 "admin_id": admin_id,
#                 "vehicles": response
#             },
#             status=200
#         )

#     except json.JSONDecodeError:
#         return JsonResponse(
#             {"error": "Invalid JSON", "status_code": 400},
#             status=400
#         )
#     except Exception as e:
#         return JsonResponse(
#             {"error": str(e), "status_code": 500},
#             status=500
#         )



@csrf_exempt
def admin_forgot_password(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        data = json.loads(request.body or "{}")
        email = data.get("email", "").strip().lower()

        if not email:
            return JsonResponse({"error": "Email required."}, status=400)

        user = SuperAdmin.objects.filter(email=email).first()
        role = "superadmin"

        if not user:
            user = Admin.objects.filter(email=email, status=1).first()
            role = "admin"

            if not user:
                return JsonResponse({"error": "Email not found"}, status=404)

            if user.approval == 1:
                return JsonResponse({"error": "Account pending approval"}, status=403)

            elif user.approval == 3:
                return JsonResponse({"error": "Account rejected"}, status=403)

            elif user.approval != 2:
                return JsonResponse({"error": "Invalid account status"}, status=403)

        otp = str(random.randint(100000, 999999))
        user.otp = otp
        user.otp_send_type = "email"
        user.changed_on = timezone.now()
        user.save()

        send_otp_email(user.email, user.name, otp)

        return JsonResponse({
            "message": "OTP sent to email",
            "email": user.email,
            "role": role
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def admin_forgot_verify_otp(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        data = json.loads(request.body or "{}")
        email = data.get("email", "").strip().lower()
        otp = data.get("otp", "").strip()

        user = SuperAdmin.objects.filter(email=email).first()

        if not user:
            user = Admin.objects.filter(email=email, status=1).first()

        if not user:
            return JsonResponse({"error": "Invalid email"}, status=404)

        if not otp.isdigit():
            return JsonResponse({"error": "OTP must be numeric"}, status=400)

        if user.otp != int(otp):
            return JsonResponse({"error": "Invalid OTP"}, status=401)

        if user.changed_on and timezone.now() > user.changed_on + timedelta(minutes=2):
            return JsonResponse({"error": "OTP expired"}, status=401)

        user.otp = None
        user.save()

        return JsonResponse({
            "message": "OTP verified successfully"
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def admin_reset_password(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    try:
        data = json.loads(request.body or "{}")
        email = data.get("email", "").strip().lower()
        new_password = data.get("new_password", "").strip()

        if not email or not new_password:
            return JsonResponse({"error": "Email and new password required."}, status=400)

        user = SuperAdmin.objects.filter(email=email).first()
        role = "superadmin"

        # ✅ If not found, check Admin
        if not user:
            user = Admin.objects.filter(email=email, status=1).first()
            role = "admin"

        if not user:
            return JsonResponse({"error": "Invalid email"}, status=404)

        # ✅ OTP must be verified (otp should be None)
        if user.otp is not None:
            return JsonResponse({"error": "OTP not verified"}, status=403)

        # ✅ Update password
        if role == "superadmin":
            user.password = new_password   # (assuming plain or custom logic)
        else:
            user.password = make_password(new_password)

        user.save()

        return JsonResponse({
            "message": "Password reset successful",
            "email": email,
            "role": role
        })

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)