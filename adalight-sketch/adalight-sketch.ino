/*
 * Adalight Arduino Sketch for Universal Ambient Light
 * 
 * This sketch is compatible with Universal Ambient Light Android app
 * Supports ADA protocol (standard Adalight)
 * 
 * Requirements:
 * - Arduino (Uno, Nano, Mega, etc.) or compatible board
 * - WS2812B LED strip (or other addressable RGB strip)
 * - USB cable for connection to Android device via OTG
 * 
 * Setup:
 * 1. Install FastLED library: https://github.com/FastLED/FastLED
 * 2. Modify constants below to match your configuration
 * 3. Upload sketch to Arduino
 * 4. Connect LED strip to DATA_PIN
 * 5. Connect Arduino to Android device via USB OTG cable
 * 
 * In the app:
 * - Connection Type: Adalight
 * - Baud Rate: 115200
 * - Protocol: ADA
 */

#include <FastLED.h>

// ========== CONFIGURATION ==========

// Pin for LED strip connection (DATA)
#define DATA_PIN 25

// Number of LEDs in the strip
// IMPORTANT: This value must match the settings in the app!
#define NUM_LEDS 300

// LED strip type (WS2812B, WS2811, SK6812, etc.)
#define LED_TYPE WS2812B

// Color order (RGB, GRB, BRG, etc.)
// For WS2812B, GRB is usually used
#define COLOR_ORDER GRB

// Brightness (0-255)
#define BRIGHTNESS 255

// Serial port speed (must match app settings)
#define SERIAL_BAUD 115200

// ========== PROTOCOL CONSTANTS ==========

#define ADA_HEADER_SIZE 6
#define ADA_MAGIC_HEADER "Ada"
#define ADA_MAGIC_BYTE 0x55

// Buffer for receiving data
#define BUFFER_SIZE (ADA_HEADER_SIZE + NUM_LEDS * 3)

// ========== GLOBAL VARIABLES ==========

CRGB leds[NUM_LEDS];
byte serialBuffer[BUFFER_SIZE];
int bufferIndex = 0;
bool headerFound = false;

// ========== FUNCTIONS ==========

void setup() {
  // Initialize Serial port
  Serial.begin(SERIAL_BAUD);
  
  // Initialize FastLED
  FastLED.addLeds<LED_TYPE, DATA_PIN, COLOR_ORDER>(leds, NUM_LEDS);
  FastLED.setBrightness(BRIGHTNESS);
  
  // Clear strip (black color)
  FastLED.clear();
  FastLED.show();
  
  // Small delay for stabilization
  delay(100);
}

void loop() {
  // Check if data is available in Serial
  if (Serial.available() > 0) {
    // Read all available bytes
    while (Serial.available() > 0) {
      byte inByte = Serial.read();
      
      // Search for "Ada" header
      if (!headerFound) {
        // Shift buffer left
        for (int i = 0; i < ADA_HEADER_SIZE - 1; i++) {
          serialBuffer[i] = serialBuffer[i + 1];
        }
        serialBuffer[ADA_HEADER_SIZE - 1] = inByte;
        
        // Check header
        if (serialBuffer[0] == 'A' && 
            serialBuffer[1] == 'd' && 
            serialBuffer[2] == 'a') {
          headerFound = true;
          bufferIndex = 3; // Start from position 3 (bytes 0-2 are already "Ada")
        }
      } else {
        // Read remaining header bytes and data
        if (bufferIndex < BUFFER_SIZE) {
          serialBuffer[bufferIndex++] = inByte;
        }
        
        // When header is fully read (6 bytes), check packet size
        if (bufferIndex >= ADA_HEADER_SIZE) {
          // Read LED count from header
          uint16_t ledCountMinusOne = (serialBuffer[3] << 8) | serialBuffer[4];
          uint16_t ledCount = ledCountMinusOne + 1;
          
          // Limit LED count to NUM_LEDS for safety
          if (ledCount > NUM_LEDS) {
            ledCount = NUM_LEDS;
          }
          
          // Calculate expected packet size
          uint16_t expectedPacketSize = ADA_HEADER_SIZE + (ledCount * 3);
          
          // Buffer overflow protection
          if (expectedPacketSize > BUFFER_SIZE) {
            // Packet too large, reset
            headerFound = false;
            bufferIndex = 0;
            continue;
          }
          
          // Check if we received complete packet
          if (bufferIndex >= expectedPacketSize) {
            processAdalightPacket();
            headerFound = false;
            bufferIndex = 0;
          }
        }
      }
    }
  }
}

void processAdalightPacket() {
  // Check header
  if (serialBuffer[0] != 'A' || 
      serialBuffer[1] != 'd' || 
      serialBuffer[2] != 'a') {
    headerFound = false;
    return;
  }
  
  // Read LED count (ledCount - 1)
  uint16_t ledCountMinusOne = (serialBuffer[3] << 8) | serialBuffer[4];
  uint16_t ledCount = ledCountMinusOne + 1;
  
  // Check checksum
  byte checksum = serialBuffer[3] ^ serialBuffer[4] ^ ADA_MAGIC_BYTE;
  if (serialBuffer[5] != checksum) {
    headerFound = false;
    return;
  }
  
  // Limit LED count to NUM_LEDS
  if (ledCount > NUM_LEDS) {
    ledCount = NUM_LEDS;
  }
  
  // Read RGB data
  int dataOffset = ADA_HEADER_SIZE;
  for (int i = 0; i < ledCount; i++) {
    byte r = serialBuffer[dataOffset++];
    byte g = serialBuffer[dataOffset++];
    byte b = serialBuffer[dataOffset++];
    
    // Set LED color
    leds[i] = CRGB(r, g, b);
  }
  
  // Update LED strip
  FastLED.show();
  
  // Reset flag for next packet
  headerFound = false;
  bufferIndex = 0;
}
