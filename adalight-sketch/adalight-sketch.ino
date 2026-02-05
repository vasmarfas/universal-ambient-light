/*
 * Universal Adalight/Hyperion Sketch (robust parser)
 *
 * Supports:
 * - ADA   (Standard Adalight):  "Ada" + (N-1) + checksum + RGB...
 * - LBAPA (LightBerry APA102):  "Ada" + (N)   + checksum + 0000 + [FF R G B]... + endframe
 * - AWA   (Hyperserial):        "Aw[a|A]" + (N-1) + checksum + RGB... + Fletcher(3)
 *
 * Key goals:
 * - Always consume the FULL packet length from the header count (even if NUM_LEDS is smaller),
 *   so the stream never desynchronizes.
 * - Auto-detect ADA vs LBAPA (best-effort) while always detecting AWA by header.
 */

#include <FastLED.h>

// ========== CONFIGURATION ==========

#define DATA_PIN 9
#define NUM_LEDS 66
#define LED_TYPE WS2812B
#define COLOR_ORDER GRB
#define BRIGHTNESS 255
#define SERIAL_BAUD 115200

// Optional watchdog for broken streams (ms). Set 0 to disable.
#define PACKET_TIMEOUT_MS 500

// 0 = auto (recommended), 1 = force ADA, 2 = force LBAPA, 3 = force AWA (only works if sender uses AWA header)
#define FORCE_PROTOCOL 0

// ========== INTERNALS ==========

enum Protocol
{
  PROTO_NONE = 0,
  PROTO_ADA,
  PROTO_LBAPA,
  PROTO_AWA
};

enum RxState
{
  RX_WAIT_A,
  RX_WAIT_D_OR_W,
  RX_WAIT_THIRD,
  RX_COUNT_HI,
  RX_COUNT_LO,
  RX_HEADER_CHECKSUM,

  // ADA/AWA RGB stream
  RX_RGB_STREAM,

  // Decide ADA vs LBAPA (only for "Ada" header)
  RX_PROBE_ADA_LBAPA,

  // LBAPA stream
  RX_LBAPA_START,
  RX_LBAPA_FRAME,
  RX_LBAPA_END,

  // AWA checksum
  RX_AWA_C1,
  RX_AWA_C2,
  RX_AWA_C3
};

CRGB leds[NUM_LEDS];

static RxState g_state = RX_WAIT_A;
static Protocol g_proto = PROTO_NONE;

// header context
static bool g_awaWhiteCalibration = false;
static uint8_t g_countHi = 0;
static uint8_t g_countLo = 0;
static uint8_t g_hdrChecksum = 0;
static uint16_t g_countVal = 0; // value as transmitted in header

// packet context (always based on header, NOT capped to NUM_LEDS)
static uint16_t g_packetLedCount = 0; // N
static uint16_t g_ledIndex = 0;       // 0..N

// ADA/AWA RGB parser
static uint8_t g_rgb[3];
static uint8_t g_rgbIdx = 0;

// LBAPA parser
static uint8_t g_lbapaStartRemaining = 0;
static uint8_t g_lbapaFrame[4];
static uint8_t g_lbapaFrameIdx = 0;
static uint16_t g_lbapaEndRemaining = 0;

// AWA Fletcher checksum (matches Hyperion implementation)
static uint16_t g_fletcher1 = 0;
static uint16_t g_fletcher2 = 0;
static uint16_t g_fletcherExt = 0;
static uint8_t g_fletcherPos = 0; // 0-based position

// Probe buffer for ADA vs LBAPA (read 5 bytes after header)
static uint8_t g_probe[5];
static uint8_t g_probeIdx = 0;

static unsigned long g_lastByteMs = 0;

static inline uint16_t endFrameSize(uint16_t ledCount)
{
  uint16_t ef = (uint16_t)((ledCount + 15) / 16);
  return (ef < 4) ? 4 : ef;
}

static void resetRx()
{
  g_state = RX_WAIT_A;
  g_proto = PROTO_NONE;
  g_awaWhiteCalibration = false;
  g_countHi = g_countLo = g_hdrChecksum = 0;
  g_countVal = 0;

  g_packetLedCount = 0;
  g_ledIndex = 0;

  g_rgbIdx = 0;
  g_lbapaStartRemaining = 0;
  g_lbapaFrameIdx = 0;
  g_lbapaEndRemaining = 0;

  g_fletcher1 = g_fletcher2 = g_fletcherExt = 0;
  g_fletcherPos = 0;

  g_probeIdx = 0;
}

static inline void fletcherUpdate(uint8_t b)
{
  // Hyperion: fletcherExt = (fletcherExt + (byte ^ position)) % 255; position starts at 0
  g_fletcherExt = (uint16_t)((g_fletcherExt + (uint16_t)(b ^ g_fletcherPos)) % 255);
  g_fletcher1 = (uint16_t)((g_fletcher1 + b) % 255);
  g_fletcher2 = (uint16_t)((g_fletcher2 + g_fletcher1) % 255);
  g_fletcherPos = (uint8_t)(g_fletcherPos + 1);
}

static inline void showAndReset()
{
  FastLED.show();
  resetRx();
}

static inline void consumeRgbByte(uint8_t b, bool doChecksum)
{
  if (doChecksum)
  {
    fletcherUpdate(b);
  }

  g_rgb[g_rgbIdx++] = b;
  if (g_rgbIdx == 3)
  {
    if (g_ledIndex < NUM_LEDS)
    {
      leds[g_ledIndex] = CRGB(g_rgb[0], g_rgb[1], g_rgb[2]);
    }
    g_ledIndex++;
    g_rgbIdx = 0;
  }
}

static void beginAda(uint16_t countVal)
{
  g_proto = PROTO_ADA;
  g_packetLedCount = (uint16_t)(countVal + 1); // header sends N-1
  g_ledIndex = 0;
  g_rgbIdx = 0;
  g_state = RX_RGB_STREAM;
}

static void beginAwa(uint16_t countVal)
{
  g_proto = PROTO_AWA;
  g_packetLedCount = (uint16_t)(countVal + 1); // header sends N-1
  g_ledIndex = 0;
  g_rgbIdx = 0;

  g_fletcher1 = g_fletcher2 = g_fletcherExt = 0;
  g_fletcherPos = 0;

  g_state = RX_RGB_STREAM;
}

static void beginLbapa(uint16_t countVal)
{
  g_proto = PROTO_LBAPA;
  g_packetLedCount = countVal; // header sends N
  g_ledIndex = 0;
  g_lbapaStartRemaining = 4;
  g_lbapaFrameIdx = 0;
  g_lbapaEndRemaining = 0;
  g_state = RX_LBAPA_START;
}

static Protocol decideAdaVsLbapa(uint16_t countVal)
{
#if FORCE_PROTOCOL == 1
  return PROTO_ADA;
#elif FORCE_PROTOCOL == 2
  return PROTO_LBAPA;
#else
  // Fast path when NUM_LEDS matches the sender:
  // ADA sends N-1, LBAPA sends N.
  if (countVal == (uint16_t)(NUM_LEDS - 1))
  {
    return PROTO_ADA;
  }
  if (countVal == (uint16_t)NUM_LEDS)
  {
    return PROTO_LBAPA;
  }

  // Unknown: we'll probe 5 bytes after header:
  // LBAPA should start with 0x00 0x00 0x00 0x00 0xFF
  return PROTO_NONE;
#endif
}

void setup()
{
  Serial.begin(SERIAL_BAUD);

  FastLED.addLeds<LED_TYPE, DATA_PIN, COLOR_ORDER>(leds, NUM_LEDS);
  FastLED.setBrightness(BRIGHTNESS);
  FastLED.clear();
  FastLED.show();

  // Startup LED test: R -> G -> B -> W (300ms each)
  const CRGB startupColors[] = {CRGB::Red, CRGB::Green, CRGB::Blue, CRGB::White};
  for (uint8_t ci = 0; ci < (sizeof(startupColors) / sizeof(startupColors[0])); ci++)
  {
    fill_solid(leds, NUM_LEDS, startupColors[ci]);
    FastLED.show();
    delay(300);
  }
  FastLED.clear();
  FastLED.show();
}

void loop()
{
#if PACKET_TIMEOUT_MS > 0
  if (g_state != RX_WAIT_A)
  {
    const unsigned long now = millis();
    if (now - g_lastByteMs > PACKET_TIMEOUT_MS)
    {
      resetRx();
    }
  }
#endif

  while (Serial.available() > 0)
  {
    const uint8_t b = (uint8_t)Serial.read();
    g_lastByteMs = millis();

    switch (g_state)
    {
      case RX_WAIT_A:
        if (b == 'A')
        {
          g_state = RX_WAIT_D_OR_W;
        }
        break;

      case RX_WAIT_D_OR_W:
        if (b == 'd')
        {
          // Ada...
          g_proto = PROTO_NONE; // not decided yet
          g_state = RX_WAIT_THIRD;
          g_awaWhiteCalibration = false;
          // mark "Ada" path by leaving whiteCalibration false and proto NONE
        }
        else if (b == 'w')
        {
          // Aw[a|A]...
          g_proto = PROTO_AWA;
          g_state = RX_WAIT_THIRD;
        }
        else
        {
          resetRx();
        }
        break;

      case RX_WAIT_THIRD:
        if (g_proto == PROTO_AWA)
        {
          if (b == 'a')
          {
            g_awaWhiteCalibration = false;
            g_state = RX_COUNT_HI;
          }
          else if (b == 'A')
          {
            g_awaWhiteCalibration = true;
            g_state = RX_COUNT_HI;
          }
          else
          {
            resetRx();
          }
        }
        else
        {
          // expecting 'a' for "Ada"
          if (b == 'a')
          {
            g_state = RX_COUNT_HI;
          }
          else
          {
            resetRx();
          }
        }
        break;

      case RX_COUNT_HI:
        g_countHi = b;
        g_state = RX_COUNT_LO;
        break;

      case RX_COUNT_LO:
        g_countLo = b;
        g_state = RX_HEADER_CHECKSUM;
        break;

      case RX_HEADER_CHECKSUM:
        g_hdrChecksum = b;
        g_countVal = (uint16_t)((g_countHi << 8) | g_countLo);

        if ((uint8_t)(g_countHi ^ g_countLo ^ 0x55) != g_hdrChecksum)
        {
          resetRx();
          break;
        }

        if (g_proto == PROTO_AWA)
        {
          beginAwa(g_countVal);
        }
        else
        {
          const Protocol decided = decideAdaVsLbapa(g_countVal);
          if (decided == PROTO_ADA)
          {
            beginAda(g_countVal);
          }
          else if (decided == PROTO_LBAPA)
          {
            beginLbapa(g_countVal);
          }
          else
          {
            // Need to probe after header to decide
            g_probeIdx = 0;
            g_state = RX_PROBE_ADA_LBAPA;
          }
        }
        break;

      case RX_PROBE_ADA_LBAPA:
        g_probe[g_probeIdx++] = b;
        if (g_probeIdx >= 5)
        {
          const bool looksLikeLbapa =
              (g_probe[0] == 0x00) && (g_probe[1] == 0x00) && (g_probe[2] == 0x00) && (g_probe[3] == 0x00) && (g_probe[4] == 0xFF);

          if (looksLikeLbapa)
          {
            // Start frame already consumed (4x 0), first LED frame prefix consumed (0xFF)
            beginLbapa(g_countVal);
            g_lbapaStartRemaining = 0;
            g_lbapaFrame[0] = 0xFF;
            g_lbapaFrameIdx = 1;
            g_state = RX_LBAPA_FRAME;
          }
          else
          {
            // Treat those 5 bytes as start of ADA RGB stream
            beginAda(g_countVal);
            for (uint8_t i = 0; i < 5; i++)
            {
              consumeRgbByte(g_probe[i], false);
              if (g_ledIndex >= g_packetLedCount && g_rgbIdx == 0)
              {
                showAndReset();
                break;
              }
            }
          }
        }
        break;

      case RX_RGB_STREAM:
        if (g_proto == PROTO_AWA)
        {
          consumeRgbByte(b, true);
          if (g_ledIndex >= g_packetLedCount && g_rgbIdx == 0)
          {
            g_state = RX_AWA_C1;
          }
        }
        else
        {
          // ADA
          consumeRgbByte(b, false);
          if (g_ledIndex >= g_packetLedCount && g_rgbIdx == 0)
          {
            showAndReset();
          }
        }
        break;

      case RX_LBAPA_START:
        // Consume start frame (4 bytes, typically 0x00)
        if (g_lbapaStartRemaining > 0)
        {
          g_lbapaStartRemaining--;
        }
        if (g_lbapaStartRemaining == 0)
        {
          g_lbapaFrameIdx = 0;
          g_state = RX_LBAPA_FRAME;
        }
        break;

      case RX_LBAPA_FRAME:
        g_lbapaFrame[g_lbapaFrameIdx++] = b;
        if (g_lbapaFrameIdx >= 4)
        {
          // Frame: [0xFF, R, G, B]
          if (g_ledIndex < NUM_LEDS)
          {
            leds[g_ledIndex] = CRGB(g_lbapaFrame[1], g_lbapaFrame[2], g_lbapaFrame[3]);
          }
          g_ledIndex++;
          g_lbapaFrameIdx = 0;

          if (g_ledIndex >= g_packetLedCount)
          {
            g_lbapaEndRemaining = endFrameSize(g_packetLedCount);
            g_state = RX_LBAPA_END;
          }
        }
        break;

      case RX_LBAPA_END:
        if (g_lbapaEndRemaining > 0)
        {
          g_lbapaEndRemaining--;
        }
        if (g_lbapaEndRemaining == 0)
        {
          showAndReset();
        }
        break;

      case RX_AWA_C1:
        if (b == (uint8_t)g_fletcher1)
        {
          g_state = RX_AWA_C2;
        }
        else
        {
          resetRx();
        }
        break;

      case RX_AWA_C2:
        if (b == (uint8_t)g_fletcher2)
        {
          g_state = RX_AWA_C3;
        }
        else
        {
          resetRx();
        }
        break;

      case RX_AWA_C3:
      {
        const uint8_t expected = (g_fletcherExt == 0x41) ? 0xaa : (uint8_t)g_fletcherExt;
        if (b == expected)
        {
          showAndReset();
        }
        else
        {
          resetRx();
        }
      }
      break;
    }
  }
}
