import os
import sys
import cv2
import numpy as np
import onnxruntime as ort
import time
import threading
import winsound
import requests
from pushbullet import Pushbullet
from telegram import Bot

# Constants
ALERT_THRESHOLD = 1.3  # Alert if drowsiness lasts more than 1.3 seconds
CALL_THRESHOLD = 5.0  # Send Telegram alert if drowsiness lasts >5 sec
BEEP_FREQUENCY = 1000  # Beep sound frequency
BEEP_DURATION = 500  # Beep duration in milliseconds

# Get the correct base path for bundled executable
def get_base_path():
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

# Initialize paths
base_path = get_base_path()
MODEL_PATH = os.path.join(base_path, "drowsiness_model.onnx")
PUSHBULLET_API_KEY = "o.ISl7vFswIo0No5HaWk4OJN0WXYtpXNSs"
TELEGRAM_BOT_TOKEN = "7906985562:AAHxuXOT6lS2i_ipuuJ9AuLIupe0mmvTfQw"
TELEGRAM_CHAT_ID = "5036175022"

# Global variables
drowsy_start_time = None
alert_triggered = False
telegram_alert_sent = False
beep_thread = None
stop_beep = False
telegram_bot = None

def get_gps_location():
    try:
        response = requests.get("http://ip-api.com/json/")
        data = response.json()
        if data["status"] == "success":
            return f"https://maps.google.com/?q={data['lat']},{data['lon']}"
        return "Unknown location"
    except Exception:
        return "Unknown location"

def send_push_notification(title, message):
    try:
        pb = Pushbullet(PUSHBULLET_API_KEY)
        pb.push_note(title, message)
    except Exception as e:
        print(f"Failed to send Pushbullet notification: {e}")

def send_telegram_alert():
    global telegram_alert_sent
    if not telegram_bot:
        return
    
    try:
        gps_location = get_gps_location()
        message = (
            "🚨 *DROWSINESS ALERT!* 🚨\n\n"
            f"⚠️ Driver has been drowsy for **{CALL_THRESHOLD} seconds!**\n"
            f"📍 Location: [Google Maps]({gps_location})\n\n"
            "Please check on them immediately!"
        )
        telegram_bot.send_message(
            TELEGRAM_CHAT_ID,
            message,
            parse_mode="Markdown"
        )
        telegram_alert_sent = True
        print("Telegram alert sent!")
    except Exception as e:
        print(f"Failed to send Telegram alert: {e}")

def play_continuous_beep():
    global stop_beep
    while not stop_beep:
        winsound.Beep(BEEP_FREQUENCY, BEEP_DURATION)
        time.sleep(0.5)

def detect_drowsiness():
    global drowsy_start_time, alert_triggered, telegram_alert_sent, beep_thread, stop_beep, telegram_bot
    
    # Initialize Telegram bot
    try:
        telegram_bot = Bot(token=TELEGRAM_BOT_TOKEN)
    except Exception as e:
        print(f"Failed to initialize Telegram bot: {e}")

    # Load ONNX model
    try:
        session = ort.InferenceSession(MODEL_PATH)
        input_name = session.get_inputs()[0].name
        input_shape = session.get_inputs()[0].shape
        required_height, required_width = input_shape[2], input_shape[3]
    except Exception as e:
        print(f"Failed to load ONNX model: {e}")
        print(f"Model path attempted: {MODEL_PATH}")
        input("Press Enter to exit...")
        return

    # Camera setup
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("Error: Could not open webcam.")
        return

    print("Drowsiness detection started. Press 'q' to exit.")

    while True:
        ret, frame = cap.read()
        if not ret:
            print("Error: Unable to capture frame.")
            break

        # Preprocess frame
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        resized = cv2.resize(gray, (required_width, required_height))
        normalized = resized / 255.0
        input_data = np.expand_dims(np.expand_dims(normalized, axis=0), axis=0).astype(np.float32)

        # Run inference
        try:
            outputs = session.run(None, {input_name: input_data})
            prediction = np.argmax(outputs[0])
        except Exception as e:
            print(f"Inference error: {e}")
            break

        # Drowsiness detection logic
        if prediction == 1:  # Drowsy
            if drowsy_start_time is None:
                drowsy_start_time = time.time()
            else:
                drowsy_duration = time.time() - drowsy_start_time
                
                if drowsy_duration >= ALERT_THRESHOLD and not alert_triggered:
                    alert_triggered = True
                    stop_beep = False
                    beep_thread = threading.Thread(target=play_continuous_beep)
                    beep_thread.start()
                    send_push_notification(
                        "Drowsiness Alert!",
                        f"Driver is drowsy! Location: {get_gps_location()}"
                    )
                
                if drowsy_duration >= CALL_THRESHOLD and not telegram_alert_sent:
                    threading.Thread(target=send_telegram_alert).start()
        else:  # Non-Drowsy
            if alert_triggered or telegram_alert_sent:
                stop_beep = True
                if beep_thread:
                    beep_thread.join()
                alert_triggered = False
                telegram_alert_sent = False
            drowsy_start_time = None

        # Display status
        status_text = "Drowsy" if prediction == 1 else "Non-Drowsy"
        color = (0, 0, 255) if prediction == 1 else (0, 255, 0)
        
        if drowsy_start_time:
            duration = time.time() - drowsy_start_time
            status_text += f" ({duration:.1f}s)"
            if duration > ALERT_THRESHOLD:
                color = (0, 165, 255)  # Orange for warning
            if duration > CALL_THRESHOLD:
                color = (0, 0, 255)  # Red for critical
        
        cv2.putText(frame, status_text, (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 1, color, 2)
        cv2.imshow("Drowsiness Detection", frame)

        if cv2.waitKey(1) & 0xFF == ord('q'):
            stop_beep = True
            if beep_thread:
                beep_thread.join()
            break

    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    detect_drowsiness()