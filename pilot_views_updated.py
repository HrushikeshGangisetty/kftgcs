import re
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.utils import timezone
from datetime import timedelta
import json
from django.conf import settings
from .models import Pilot, Admin
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
        first_name = data.get('first_name')
        last_name = data.get('last_name')
        email = data.get('email')
        mobile_no = data.get('mobile_no')
        password = data.get('password')
        re_password = data.get('re_password')

        if not all([first_name, last_name, email, mobile_no, password, re_password]):
            return JsonResponse(
                {"error": "All fields are required: first_name, last_name, email, mobile_no, password, re_password.",
                 "status_code": 400}, status=400
            )

        password_error = is_valid_password(password)
        if password_error:
            return JsonResponse({"error": password_error, "status_code": 400}, status=400)

        if password != re_password:
            return JsonResponse({"error": "Passwords do not match.", "status_code": 400}, status=400)

        existing_email = Pilot.objects.filter(email=email).first()
        if existing_email:
            if existing_email.password is None:
                return JsonResponse(
                    {"error": "Email registered via Google Sign-In. Please reset your password.", "status_code": 409},
                    status=409
                )
            return JsonResponse({"error": "Email already exists.", "status_code": 409}, status=409)

        if Pilot.objects.filter(mobile_no=mobile_no).exists():
            return JsonResponse({"error": "Mobile number already exists.", "status_code": 409}, status=409)

        admin = Admin.objects.order_by('id').first()
        if not admin:
            return JsonResponse({"error": "No admin found in the system.", "status_code": 500}, status=500)

        with transaction.atomic():
            pilot = Pilot.objects.create(
                first_name=first_name,
                last_name=last_name,
                email=email,
                mobile_no=mobile_no,
                password=make_password(password),
                admin=admin
            )

            otp = generate_otp()
            pilot.otp = otp
            pilot.changed_on = timezone.now()
            pilot.save()

            send_verification_email(email, first_name, otp)

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

def send_verification_email(email, first_name, otp):
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
                Please use the OTP below to verify your email.This OTP is valid for <b>2 minutes</b>
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


@csrf_exempt
def verify_pilot_otp(request):
    if request.method != "POST":
        return JsonResponse({"error": "Invalid method"}, status=405)

    try:
        data = json.loads(request.body)
        email = data.get("email")
        otp = data.get("otp")
        device_id = data.get("device_id")  # ✅ Android ANDROID_ID sent by app

        if not email or not otp:
            return JsonResponse({"error": "email and otp required"}, status=400)

        if not device_id:
            return JsonResponse({"error": "device_id is required", "status_code": 400}, status=400)

        pilot = Pilot.objects.filter(email=email).first()
        if not pilot or not pilot.otp:
            return JsonResponse({"error": "Invalid or expired OTP"}, status=400)

        # ⏱️ OTP Expiry Check (2 minutes)
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

        # ✅ OTP VALID — Store device_id from Android app
        pilot.register_status = 1
        pilot.device_id = device_id
        pilot.otp = None
        pilot.changed_on = timezone.now()
        pilot.save()

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

        pilot = Pilot.objects.filter(email=email).first()
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

        send_verification_email(
            pilot.email,
            pilot.first_name,
            new_otp
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
        device_id = data.get("device_id")  # ✅ Android ANDROID_ID sent by app

        if not email or not password:
            return JsonResponse(
                {"error": "Email and password are required"},
                status=400
            )

        if not device_id:
            return JsonResponse(
                {"error": "device_id is required", "status_code": 400},
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

        # Admin not approved
        if pilot.admin_approved != 1:
            return JsonResponse({
                "error": "Account pending admin approval.",
                "status_code": 403
            }, status=403)

        # ✅ Device check — compare device_id from app against stored device_id
        if pilot.device_id and pilot.device_id != device_id:
            return JsonResponse({
                "error": "Login blocked. Account already active on another device.",
                "status_code": 403
            }, status=403)

        # Successful login
        pilot.login_on = timezone.now()
        pilot.login_status = 1
        pilot.logout_status = 0
        pilot.save(update_fields=["login_on", "login_status", "logout_status"])

        return JsonResponse({
            "message": "Login successful",
            "pilot_id": pilot.id,
            "status_code": 200
        }, status=200)

    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON format"}, status=400)
    except Exception as e:
        return JsonResponse({"error": str(e)}, status=500)

@csrf_exempt
def view_pilots(request):
    if request.method != "POST":
        return JsonResponse({"error": "Only POST allowed."}, status=405)

    admin_id = request.session.get('admin_id')
    if not admin_id:
        return JsonResponse({"error": "Unauthorized, session expired or not logged in"}, status=401)

    pilots = Pilot.objects.all()

    pilot_list = []
    for pilot in pilots:
        pilot_list.append({
            "id": pilot.id,
            "name": f"{pilot.first_name} {pilot.last_name}",
            "email": pilot.email,
            "mobile_no": pilot.mobile_no,
            "admin_approved": pilot.admin_approved,
            "login_on": pilot.login_on,
            "login_status": pilot.login_status,
            "logout_status": pilot.logout_status,
            "logout_on": pilot.logout_on,
            "device_id": pilot.device_id,
            "device_limit": pilot.device_limit,
            "created_on": pilot.created_on,
        })

    return JsonResponse({"pilots": pilot_list})

@csrf_exempt
def pilot_logout(request):
    if request.method != "POST":
        return JsonResponse({"error": "Invalid method"}, status=405)

    data = json.loads(request.body)
    email = data.get("email")

    pilot = Pilot.objects.filter(email=email).first()
    if not pilot:
        return JsonResponse({"error": "Pilot not found"}, status=404)

    pilot.logout_on = timezone.now()
    pilot.login_status = 0
    pilot.logout_status = 1
    pilot.save()

    return JsonResponse({"message": "Logged out successfully"})

