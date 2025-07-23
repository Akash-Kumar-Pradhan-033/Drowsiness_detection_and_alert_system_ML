# portable_drowsiness_detection.py
import os
import sys
import cv2
import numpy as np
import onnxruntime as ort
import time
import threading
import json
import platform
import requests
from pathlib import Path

# Constants from original code
MODEL_PATH = r"C:\Users\ommak\Drowsiness detection and alert system\Model 3\drowsiness_model.onnx"
PUSHBULLET_API_KEY = "your token"
TELEGRAM_BOT_TOKEN = "your token"
TELEGRAM_CHAT_ID = "Your ID"
ALERT_THRESHOLD = 1.3
CALL_THRESHOLD = 5.0
BEEP_FREQUENCY = 1000
BEEP_DURATION = 500

# Configuration management
class Config:
    def __init__(self):
        self.base_path = self.get_base_path()
        self.config_path = os.path.join(self.base_path, "config.json")
        self.default_config = {
            "ALERT_THRESHOLD": ALERT_THRESHOLD,
            "CALL_THRESHOLD": CALL_THRESHOLD,
            "BEEP_FREQUENCY": BEEP_FREQUENCY,
            "BEEP_DURATION": BEEP_DURATION,
            "MODEL_PATH": MODEL_PATH,
            "PUSHBULLET_API_KEY": PUSHBULLET_API_KEY,
            "TELEGRAM_BOT_TOKEN": TELEGRAM_BOT_TOKEN,
            "TELEGRAM_CHAT_ID": TELEGRAM_CHAT_ID
        }
        self.config = self.load_config()
    
    def get_base_path(self):
        """Get the path where the executable is running from"""
        if getattr(sys, 'frozen', False):
            return os.path.dirname(sys.executable)
        else:
            return os.path.dirname(os.path.abspath(__file__))
    
    def load_config(self):
        """Load or create config file"""
        try:
            if os.path.exists(self.config_path):
                with open(self.config_path, 'r') as f:
                    return json.load(f)
            else:
                self.save_config(self.default_config)
                return self.default_config
        except Exception as e:
            print(f"Error loading config: {e}")
            return self.default_config
    
    def save_config(self, config):
        """Save config to file"""
        try:
            with open(self.config_path, 'w') as f:
                json.dump(config, f, indent=4)
        except Exception as e:
            print(f"Error saving config: {e}")
    
    def get(self, key):
        """Safe method to get config values"""
        return self.config.get(key, self.default_config.get(key))

# Audio alerts with platform-specific implementations
class AudioAlert:
    def __init__(self, config):
        self.config = config
        self.stop_beep = False
        self.platform = platform.system()
    
    def play_beep(self):
        if self.platform == "Windows":
            import winsound
            while not self.stop_beep:
                winsound.Beep(
                    self.config.get("BEEP_FREQUENCY"),
                    self.config.get("BEEP_DURATION")
                )
                time.sleep(0.5)
        elif self.platform == "Linux":
            import os
            while not self.stop_beep:
                os.system(f'play -nq -t alsa synth {self.config.get("BEEP_DURATION")/1000} sine {self.config.get("BEEP_FREQUENCY")}')
                time.sleep(0.5)
        else:
            import sys
            while not self.stop_beep:
                sys.stdout.write('\a')
                sys.stdout.flush()
                time.sleep(0.5)

# GPS location function from original code
def get_gps_location():
    try:
        response = requests.get("http://ip-api.com/json/")
        data = response.json()
        if data["status"] == "success":
            lat = data["lat"]
            lon = data["lon"]
            return f"https://maps.google.com/?q={lat},{lon}"
        else:
            return "Unknown location"
    except Exception as e:
        print(f"Error fetching location: {e}")
        return "Unknown location"

# Main detection class
class DrowsinessDetector:
    def __init__(self, config):
        self.config = config
        self.drowsy_start_time = None
        self.alert_triggered = False
        self.telegram_alert_sent = False
        self.beep_thread = None
        self.audio_alert = AudioAlert(config)
        
        # Initialize ONNX runtime
        model_path = os.path.join(config.base_path, config.get("MODEL_PATH"))
        self.session = ort.InferenceSession(model_path)
        self.input_name = self.session.get_inputs()[0].name
        self.input_shape = self.session.get_inputs()[0].shape
    
    def detect(self):
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

            # Preprocess and predict
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            resized = cv2.resize(gray, (64, 64))
            normalized = resized / 255.0
            input_data = np.expand_dims(np.expand_dims(normalized, axis=0), axis=0).astype(np.float32)
            
            outputs = self.session.run(None, {self.input_name: input_data})
            prediction = np.argmax(outputs[0])

            # Handle drowsiness state
            self.handle_drowsiness_state(prediction)

            # Display status
            self.display_status(frame, prediction)

            # Exit on 'q' key press
            if cv2.waitKey(1) & 0xFF == ord('q'):
                self.cleanup()
                break

        cap.release()
        cv2.destroyAllWindows()
    
    def handle_drowsiness_state(self, prediction):
        if prediction == 1:  # Drowsy
            if self.drowsy_start_time is None:
                self.drowsy_start_time = time.time()
            else:
                drowsy_duration = time.time() - self.drowsy_start_time
                
                if drowsy_duration >= self.config.get("ALERT_THRESHOLD") and not self.alert_triggered:
                    self.trigger_alert()
                
                if drowsy_duration >= self.config.get("CALL_THRESHOLD") and not self.telegram_alert_sent:
                    self.send_telegram_alert()
        else:  # Non-Drowsy
            if self.alert_triggered or self.telegram_alert_sent:
                self.reset_alerts()
            self.drowsy_start_time = None
    
    def trigger_alert(self):
        self.alert_triggered = True
        self.audio_alert.stop_beep = False
        self.beep_thread = threading.Thread(target=self.audio_alert.play_beep)
        self.beep_thread.start()
        self.send_push_notification()
    
    def reset_alerts(self):
        self.audio_alert.stop_beep = True
        if self.beep_thread:
            self.beep_thread.join()
        self.alert_triggered = False
        self.telegram_alert_sent = False
    
    def send_push_notification(self):
        if self.config.get("PUSHBULLET_API_KEY"):
            try:
                from pushbullet import Pushbullet
                pb = Pushbullet(self.config.get("PUSHBULLET_API_KEY"))
                pb.push_note(
                    "Drowsiness Alert!",
                    f"Driver is drowsy! Location: {get_gps_location()}"
                )
            except Exception as e:
                print(f"Failed to send Pushbullet notification: {e}")
    
    def send_telegram_alert(self):
        if self.config.get("TELEGRAM_BOT_TOKEN") and self.config.get("TELEGRAM_CHAT_ID"):
            try:
                import telebot
                bot = telebot.TeleBot(self.config.get("TELEGRAM_BOT_TOKEN"))
                gps_location = get_gps_location()
                message = (
                    "🚨 *DROWSINESS ALERT!* 🚨\n\n"
                    f"⚠️ Driver has been drowsy for **{self.config.get('CALL_THRESHOLD')} seconds!**\n"
                    f"📍 Location: [Google Maps]({gps_location})\n\n"
                    "Please check on them immediately!"
                )
                bot.send_message(
                    self.config.get("TELEGRAM_CHAT_ID"),
                    message,
                    parse_mode="Markdown"
                )
                self.telegram_alert_sent = True
            except Exception as e:
                print(f"Failed to send Telegram alert: {e}")
    
    def display_status(self, frame, prediction):
        status_text = "Drowsy" if prediction == 1 else "Non-Drowsy"
        color = (0, 0, 255) if prediction == 1 else (0, 255, 0)
        
        if self.drowsy_start_time:
            duration = time.time() - self.drowsy_start_time
            status_text += f" ({duration:.1f}s)"
            if duration > self.config.get("ALERT_THRESHOLD"):
                color = (0, 165, 255)
            if duration > self.config.get("CALL_THRESHOLD"):
                color = (0, 0, 255)
        
        cv2.putText(frame, status_text, (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 1, color, 2)
        cv2.imshow("Drowsiness Detection", frame)
    
    def cleanup(self):
        self.reset_alerts()

if __name__ == "__main__":
    config = Config()
    detector = DrowsinessDetector(config)
    detector.detect()
