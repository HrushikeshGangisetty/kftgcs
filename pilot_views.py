import re
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.utils import timezone
from datetime import timedelta
import json
from django.conf import settings

from pavaman_gcs_app.indiantime import format_datetime_ist
from .models import Pilot, Admin, PilotDeviceAccess, SuperAdmin
from django.contrib.auth.hashers import make_password, check_password
import random
from django.core.mail import EmailMultiAlternatives
from django.db import IntegrityError, transaction
from decouple import config
import requests


def is_valid_password(password):
    if len(password) < 8:
        return "Password must be at least 8 characters long."
    if not any(char.isdigit() for char in password):
        return "Password must contain at least one digit."
    if not any(char.isupper() for char in password):
        return "Password must contain at least one uppercase letter."
    if not any(char.islower() for char in password):
        return "Password must contain at least one lowercase letter."
    if not re.search(r"[!@#$%^&*(),.?\":{}|<>]", password):
        return "Password must contain at least one special character."
    return None
def match_password(password, re_password):
    if password != re_password:
        return "Passwords must be same."
    return None
def generate_otp():
    return random.randint(100000, 999999)

@csrf_exempt
def pilot_register(request):
    if request.method != 'POST':
        return JsonResponse({"error": "Invalid HTTP method. Only POST allowed.", "status_code": 405}, status=405)

    try:
        data = json.loads(request.body)
        company_name = data.get("company_name").upper()
        first_name = data.get('first_name')
        last_name = data.get('last_name')
        email = data.get('email')
        mobile_no = data.get('mobile_no')
        password = data.get('password')
        re_password = data.get('re_password')

        if not all([company_name,first_name, last_name, email, mobile_no, password, re_password]):
            return JsonResponse(
                {"error": "All fields are required: company_name, first_name, last_name, email, mobile_no, password, re_password.",
                 "status_code": 400}, status=400
            )

        password_error = is_valid_password(password)
        if password_error:
            return JsonResponse({"error": password_error, "status_code": 400}, status=400)

        if password != re_password:
            return JsonResponse({"error": "Passwords do not match.", "status_code": 400}, status=400)

        existing_email = Pilot.objects.filter(email=email,status=1).first()
        if existing_email:
            if existing_email.password is None:
                return JsonResponse(
                    {"error": "Email registered via Google Sign-In. Please reset your password.", "status_code": 409},
                    status=409
                )
            return JsonResponse({"error": "Email already exists.", "status_code": 409}, status=409)

        if Pilot.objects.filter(mobile_no=mobile_no,status=1).exists():
            return JsonResponse({"error": "Mobile number already exists.", "status_code": 409}, status=409)

        superadmin = SuperAdmin.objects.order_by('id').first()
        if not superadmin:
            return JsonResponse({"error": "No super admin found in the system.", "status_code": 500}, status=500)

        admin = Admin.objects.filter(name__iexact=company_name).first()
        if not admin:
            return JsonResponse({
                "error": "No admin found for this company name."
            }, status=404)

        with transaction.atomic():
            pilot = Pilot.objects.create(
                company_name=company_name,
                first_name=first_name,
                last_name=last_name,
                email=email,
                mobile_no=mobile_no,
                password=make_password(password),
                admin=admin,
                superadmin=superadmin
            )

            otp = generate_otp()
            pilot.otp = otp
            pilot.changed_on = timezone.now()
            pilot.save()
            time=2
            send_verification_email(email, first_name, otp,time)

        return JsonResponse({
            "message": "Account created successfully. Verification email sent to your email. Please verify to continue.",
            "id": pilot.id,
            "status_code": 200
        }, status=200)

    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON data.", "status_code": 400}, status=400)
    except IntegrityError:
        return JsonResponse({"error": "Database integrity error.", "status_code": 500}, status=500)
    except Exception as e:
        return JsonResponse({"error": f"Unexpected error: {str(e)}", "status_code": 500}, status=500)

def send_verification_email(email, first_name, otp,time):
    subject = "[Pavaman] Please Verify Your Email"
    logo_url = f"{settings.AWS_S3_BUCKET_URL}/aviation-logo.png"

    text_content = f"""
    Hello {first_name},
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
                Hello {first_name},
            </div>

             <p style="color: #555; margin-bottom: 30px;">
                Please use the OTP below to verify your email.This OTP is valid for <b>{time} minutes</b>
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

    email_message = EmailMultiAlternatives(
        subject, text_content, settings.DEFAULT_FROM_EMAIL, [email]
    )
    email_message.attach_alternative(html_content, "text/html")
    email_message.send()

def get_client_ip(request):
    x_forwarded_for = request.META.get('HTTP_X_FORWARDED_FOR')
    if x_forwarded_for:
        return x_forwarded_for.split(',')[0].strip()
    return request.META.get('REMOTE_ADDR')

@csrf_exempt
def verify_pilot_otp(request):
    if request.method != "POST":
        return JsonResponse({"error": "Invalid method"}, status=405)

    try:
        data = json.loads(request.body)
        email = data.get("email")
        otp = data.get("otp")

        if not email or not otp:
            return JsonResponse({"error": "email and otp required"}, status=400)

        pilot = Pilot.objects.filter(email=email,status=1).first()
        if not pilot or not pilot.otp:
            return JsonResponse({"error": "Invalid or expired OTP"}, status=400)

        expiry_time = pilot.changed_on + timedelta(minutes=2)
        if timezone.now() > expiry_time:
            pilot.otp = None
            pilot.save(update_fields=["otp"])

            return JsonResponse({
                "error": "OTP expired. Please request a new verification OTP.",
                "resend_required": True,
                "status_code": 410
            }, status=410)

        if int(pilot.otp) != int(otp):
            return JsonResponse({"error": "Invalid OTP"}, status=400)

        # client_ip = get_client_ip(request)

        pilot.register_status = 1
        # pilot.device_id = client_ip
        pilot.otp = None
        pilot.changed_on = timezone.now()
        pilot.save()

        client_ip = get_client_ip(request)
        access_exists = PilotDeviceAccess.objects.filter(
            pilot=pilot
        ).exists()

        if not access_exists:
            PilotDeviceAccess.objects.create(
                pilot=pilot,
                admin=pilot.admin,
                ip_address=client_ip,
                # admin_approved=1,        # APPROVED
                # is_active=True
            )
        return JsonResponse({
            "message": "Account verified successfully. Once admin approves, you can login.",
            "status_code": 200
        }, status=200)

    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def resend_pilot_verification_otp(request):
    if request.method != "POST":
        return JsonResponse(
            {"error": "Invalid HTTP method. Only POST allowed.", "status_code": 405},
            status=405
        )

    try:
        data = json.loads(request.body)
        email = data.get("email")

        if not email:
            return JsonResponse(
                {"error": "Email is required.", "status_code": 400},
                status=400
            )

        pilot = Pilot.objects.filter(email=email,status=1).first()
        if not pilot:
            return JsonResponse(
                {"error": "Pilot account not found.", "status_code": 404},
                status=404
            )

        # Already verified
        if pilot.register_status == 1:
            return JsonResponse(
                {"error": "Account already verified.", "status_code": 409},
                status=409
            )

        # Generate & send new OTP
        new_otp = generate_otp()
        pilot.otp = new_otp
        pilot.changed_on = timezone.now()
        pilot.save(update_fields=["otp", "changed_on"])
        time=2
        send_verification_email(
            pilot.email,
            pilot.first_name,
            new_otp,
            time
        )

        return JsonResponse({
            "message": "Verification OTP resent successfully.",
            "status_code": 200
        }, status=200)

    except json.JSONDecodeError:
        return JsonResponse(
            {"error": "Invalid JSON format.", "status_code": 400},
            status=400
        )
    except Exception as e:
        return JsonResponse(
            {"error": f"Unexpected error: {str(e)}", "status_code": 500},
            status=500
        )

@csrf_exempt
def pilot_login(request):
    if request.method != "POST":
        return JsonResponse({"error": "Invalid method"}, status=405)

    try:
        data = json.loads(request.body)
        email = data.get("email")
        password = data.get("password")

        if not email or not password:
            return JsonResponse(
                {"error": "Email and password are required"},
                status=400
            )

        pilot = Pilot.objects.filter(email=email, status=1).first()
        if not pilot:
            return JsonResponse({"error": "Invalid credentials"}, status=401)

        # Password check
        if not check_password(password, pilot.password):
            return JsonResponse({"error": "Invalid credentials"}, status=401)

        # Not verified
        if pilot.register_status != 1:
            return JsonResponse({
                "error": "Account not verified. Please verify using OTP.",
                "status_code": 403
            }, status=403)
        client_ip = get_client_ip(request)
        active_device = PilotDeviceAccess.objects.filter(
            pilot=pilot,
            ip_address=client_ip,
            admin_approved=1,
            is_active=True
        ).first()
        if not active_device:
            device_request, created = PilotDeviceAccess.objects.get_or_create(
                pilot=pilot,
                ip_address=client_ip,
                defaults={
                    "admin": pilot.admin,
                    "admin_approved": 0,   # PENDING
                    "is_active": False,
                    "is_read": False,
                    "device_status":"OLD"
                }
            )
        # Admin not approved
        # if pilot.admin_approved != 1:
        #     return JsonResponse({
        #         "error": "Account pending admin approval.",
        #         "status_code": 403
        #     }, status=403)


        # Different device/IP
        # if pilot.device_id and pilot.device_id != client_ip:
        #     return JsonResponse({
        #         "error": "Login blocked. Account already active on another device.",
        #         "status_code": 403
        #     }, status=403)
        # Send OTP only once
            if device_request.admin_approved == 0:
                # otp = generate_otp()
                # device_request.otp = otp
                # device_request.changed_on = timezone.now()
                # device_request.save()

                # send_verification_email(
                #     pilot.email,
                #     pilot.first_name,
                #     otp
                # )

                notify_pilot_new_device(pilot, client_ip)
                notify_admin_new_device(pilot, client_ip)
            return JsonResponse({
                "error": "New device detected. Admin approval required.",
                "status_code": 403
            }, status=403)

        # Successful login
        pilot.login_on = timezone.now()
        pilot.login_status = 1
        pilot.logout_status = 0
        # pilot.device_id = client_ip
        pilot.save(update_fields=["login_on", "login_status", "logout_status"])

        return JsonResponse({
            "message": "Login successful",
            "pilot_id": pilot.id,
            "admin_id": pilot.admin_id,
            "company_name": pilot.company_name,
            "status_code": 200
        }, status=200)

    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON format"}, status=400)
    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)
from django.core.mail import EmailMultiAlternatives
from django.conf import settings

def notify_pilot_new_device(pilot, ip):
    subject = "[Pavaman] New Device Login Detected"
    logo_url = f"{settings.AWS_S3_BUCKET_URL}/aviation-logo.png"

    text_content = f"""
Hello {pilot.first_name},

A login attempt from a new device/IP was detected.

IP Address: {ip}

For security reasons, this login is blocked.
Please contact your admin for approval.
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
        </style>
    </head>
    <body style="margin:0;padding:0;font-family:'Inter',sans-serif;background-color:#f5f5f5;">
        <div class="container" style="margin:40px auto;background:#fff;border-radius:20px;
             box-shadow:0 4px 20px rgba(0,0,0,0.1);padding:40px 30px;
             max-width:480px;">

            <div style="text-align:center;">
                <img src="{logo_url}" class="logo" style="max-width:280px;margin-bottom:20px;" />
                <h2 style="color:#222;">New Device Login Detected</h2>
            </div>

            <p style="color:#555;font-size:14px;">Hello {pilot.first_name},</p>

            <p style="color:#555;font-size:14px;">
                We detected a login attempt from a <b>new device or IP address</b>.
            </p>

            <div style="background:#f2f2f2;padding:12px;border-radius:10px;
                        font-size:14px;color:#333;margin:20px 0;">
                <b>IP Address:</b> {ip}
            </div>

            <p style="color:#555;">
                For security reasons, this login has been blocked.
                Please contact your <b>Admin</b> and request approval.
            </p>

            <p style="font-size:14px;color:#888;margin-top:30px;">
                Once approved, you will be able to log in successfully.
            </p>

            <p style="margin-top:30px;font-size:14px;color:#888;">
                Disclaimer: This is an automated email. Please do not reply.
            </p>
        </div>
    </body>
    </html>
    """

    email = EmailMultiAlternatives(
        subject, text_content, settings.DEFAULT_FROM_EMAIL, [pilot.email]
    )
    email.attach_alternative(html_content, "text/html")
    email.send()

def notify_admin_new_device(pilot, ip):
    subject = "[Pavaman] Pilot New Device Approval Required"
    logo_url = f"{settings.AWS_S3_BUCKET_URL}/aviation-logo.png"

    text_content = f"""
Dear Admin,

A pilot has attempted to log in from a new device.

Pilot: {pilot.first_name} {pilot.last_name}
Email: {pilot.email}
Mobile: {pilot.mobile_no}
IP: {ip}
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
        </style>
    </head>
    <body style="margin:0;padding:0;font-family:'Inter',sans-serif;background:#f5f5f5;">
        <div class="container" style="margin:40px auto;background:#fff;border-radius:20px;
             box-shadow:0 4px 20px rgba(0,0,0,0.1);padding:40px 30px;
             max-width:520px;">

            <div style="text-align:center;">
                <img src="{logo_url}" class="logo" style="max-width:280px;margin-bottom:20px;" />
                <h2 style="color:#222;">New Device Approval Required</h2>
            </div>

            <p style="color:#555;font-size:14px;">Dear Admin,</p>

            <p style="color:#555;font-size:14px;">
                A pilot has attempted to log in from a <b>new device</b>.
                Please verify and approve the request.
            </p>

            <div style="background:#f2f2f2;padding:15px;border-radius:12px;
                        font-size:14px;color:#333;margin:20px 0;">
                <p><b>Pilot Name:</b> {pilot.first_name} {pilot.last_name}</p>
                <p><b>Email:</b> {pilot.email}</p>
                <p><b>Mobile:</b> {pilot.mobile_no}</p>
                <p><b>IP Address:</b> {ip}</p>
            </div>

            <p style="color:#555;">
                The pilot has been instructed to contact you and share the OTP
                (if applicable) for verification.
            </p>

            <p style="font-size:14px;color:#888;margin-top:30px;">
                Please approve or reject the request from the admin panel.
            </p>

            <p style="margin-top:30px;font-size:14px;color:#888;">
                Disclaimer: This is an automated email. Please do not reply.
            </p>
        </div>
    </body>
    </html>
    """

    email = EmailMultiAlternatives(
        subject, text_content, settings.DEFAULT_FROM_EMAIL, [pilot.admin.email]
    )
    email.attach_alternative(html_content, "text/html")
    email.send()

@csrf_exempt
def admin_send_device_otp(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed"}, status=405)

    try:
        data = json.loads(request.body)
        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        if not role or not user_id:
            return JsonResponse({
                "error": "Unauthorized. Please login."
            }, status=401)


        pilot_email = data.get("pilot_email")

        if not pilot_email:
            return JsonResponse(
                {"error": "pilot_email is required"},
                status=400
            )

        pilot_email = pilot_email.strip().lower()

        pilot = Pilot.objects.filter(email=pilot_email,status=1).first()
        if not pilot:
            return JsonResponse(
                {"error": "Pilot not found"},
                status=404
            )
        if role == "superadmin":
            if view_as_admin_id:
                # 🔵 Viewing as selected admin
                if pilot.admin_id != view_as_admin_id:
                    return JsonResponse({
                        "error": "You can only access selected admin pilots"
                    }, status=403)
            else:
                # 🟢 Own company only
                if not (
                    pilot.admin.superadmin_id == user_id and
                    pilot.admin.is_superadmin_company is True
                ):
                    return JsonResponse({
                        "error": "You can only manage your company pilots"
                    }, status=403)

        elif role == "admin":
            if pilot.admin_id != user_id:
                return JsonResponse({
                    "error": "You can only access your pilots"
                }, status=403)

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)

        device = PilotDeviceAccess.objects.filter(
            pilot=pilot,
            # admin_approved__in=[0, 1],
            # is_active=False
        ).order_by("-created_on").first()

        if not device:
            return JsonResponse(
                {"error": "No pending device request found"},
                status=404
            )
        if device.admin_approved == 1 and device.is_active== True:
            return JsonResponse({
                "error": "Latest device already verified. OTP not required."
            }, status=400)
        now = timezone.now()
        otp_validity_minutes = 10
        # otp = device.otp

        if device.otp and device.changed_on:
            expiry_time = device.changed_on + timedelta(minutes=otp_validity_minutes)

            if now >= expiry_time:
                # OTP expired → generate new
                # otp = generate_otp()
                device.otp =  generate_otp()
                device.changed_on = now
        else:
            # First time OTP
            # otp = generate_otp()
            device.otp =  generate_otp()
            device.changed_on = now

        # otp = generate_otp()

        # device.otp = otp
        # device.changed_on = now
        device.admin_approved = 1
        device.save(update_fields=["otp", "changed_on", "admin_approved"])
        time=otp_validity_minutes
        send_verification_email(
            pilot.email,
            pilot.first_name,
            device.otp,
            time
        )

        return JsonResponse({
            "message": "Verification OTP sent to pilot email",
            "status_code": 200
        }, status=200)

    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON format"}, status=400)
    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def admin_verify_and_approve_device(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed"}, status=405)

    try:
        data = json.loads(request.body)
        role = request.session.get("role")
        user_id = request.session.get("user_id")
        view_as_admin_id = request.session.get("view_as_admin_id")

        if not role or not user_id:
            return JsonResponse({
                "error": "Unauthorized. Please login."
            }, status=401)

        pilot_email = data.get("pilot_email")
        otp = data.get("otp")

        if not pilot_email or not otp:
            return JsonResponse(
                {"error": "pilot_email and otp are required"},
                status=400
            )

        pilot_email = pilot_email.strip().lower()

        pilot = Pilot.objects.filter(email=pilot_email,status=1).first()
        if not pilot:
            return JsonResponse(
                {"error": "Pilot not found"},
                status=404
            )
        if role == "superadmin":
            if view_as_admin_id:
                # 🔵 Viewing selected admin
                if pilot.admin_id != view_as_admin_id:
                    return JsonResponse({
                        "error": "You can only access selected admin pilots"
                    }, status=403)
            else:
                # 🟢 Own company
                if not (
                    pilot.admin.superadmin_id == user_id and
                    pilot.admin.is_superadmin_company is True
                ):
                    return JsonResponse({
                        "error": "You can only manage your company pilots"
                    }, status=403)

        elif role == "admin":
            if pilot.admin_id != user_id:
                return JsonResponse({
                    "error": "You can only access your pilots"
                }, status=403)

        else:
            return JsonResponse({"error": "Invalid role"}, status=403)


        # Find OTP_SENT device
        device = PilotDeviceAccess.objects.filter(
            pilot=pilot,
            otp=otp,
            admin_approved=1,   # OTP_SENT
            is_active=False
        ).order_by("-changed_on").first()

        if not device:
            return JsonResponse(
                {"error": "Invalid OTP or device request not found"},
                status=400
            )
        if not device.changed_on:
            return JsonResponse({"error": "OTP expired"}, status=400)

        expiry_time = device.changed_on + timedelta(minutes=10)
        if timezone.now() > expiry_time:
            return JsonResponse(
                {"error": "OTP expired. Please request a new OTP."},
                status=400
            )
        # Deactivate all previously active devices
        PilotDeviceAccess.objects.filter(
            pilot=pilot,
            is_active=True
        ).update(is_active=False)

        # Approve & activate this device
        # device.admin_approved = 2   # APPROVED
        device.is_active = True
        device.otp = None           # OTP cannot be reused
        device.changed_on = timezone.now()
        device.save(update_fields=[
            # "admin_approved",
            "is_active",
            "otp",
            "changed_on"
        ])

        return JsonResponse({
            "message": "Device verified and approved successfully",
            "status_code": 200
        }, status=200)

    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON format"}, status=400)
    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def pilot_logout(request):
    if request.method != "POST":
        return JsonResponse({"error": "Invalid method"}, status=405)

    try:
        data = json.loads(request.body)
        email = data.get("email")

        if not email:
            return JsonResponse({"error": "Email is required"}, status=400)

        pilot = Pilot.objects.filter(email=email, status=1).first()
        if not pilot:
            return JsonResponse({"error": "Pilot not found"}, status=404)

        pilot.logout_on = timezone.now()
        pilot.login_status = 0
        pilot.logout_status = 1
        pilot.save(update_fields=["logout_on", "login_status", "logout_status"])

        return JsonResponse({"message": "Logged out successfully"})

    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON format"}, status=400)
    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

