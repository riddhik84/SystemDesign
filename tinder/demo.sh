#!/bin/bash

# Tinder System Design Demo Script
# This script demonstrates the core functionality of the Tinder application

BASE_URL="http://localhost:8080/api"

echo "=== Tinder System Design Demo ==="
echo ""

# Create User 1 (Alice)
echo "1. Creating Alice's profile..."
ALICE_RESPONSE=$(curl -s -X POST $BASE_URL/profile \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Alice",
    "age": 28,
    "gender": "FEMALE",
    "interestedIn": "MALE",
    "ageMin": 25,
    "ageMax": 35,
    "maxDistance": 20,
    "latitude": 37.7749,
    "longitude": -122.4194,
    "bio": "Love hiking and coffee ☕"
  }')

ALICE_ID=$(echo $ALICE_RESPONSE | jq -r '.id')
echo "✓ Alice created with ID: $ALICE_ID"
echo ""

# Create User 2 (Bob)
echo "2. Creating Bob's profile..."
BOB_RESPONSE=$(curl -s -X POST $BASE_URL/profile \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bob",
    "age": 30,
    "gender": "MALE",
    "interestedIn": "FEMALE",
    "ageMin": 24,
    "ageMax": 32,
    "maxDistance": 25,
    "latitude": 37.7849,
    "longitude": -122.4094,
    "bio": "Software engineer, enjoy cooking 🍳"
  }')

BOB_ID=$(echo $BOB_RESPONSE | jq -r '.id')
echo "✓ Bob created with ID: $BOB_ID"
echo ""

# Create User 3 (Charlie)
echo "3. Creating Charlie's profile..."
CHARLIE_RESPONSE=$(curl -s -X POST $BASE_URL/profile \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Charlie",
    "age": 32,
    "gender": "MALE",
    "interestedIn": "FEMALE",
    "ageMin": 26,
    "ageMax": 34,
    "maxDistance": 30,
    "latitude": 37.7949,
    "longitude": -122.3994,
    "bio": "Musician and traveler 🎸"
  }')

CHARLIE_ID=$(echo $CHARLIE_RESPONSE | jq -r '.id')
echo "✓ Charlie created with ID: $CHARLIE_ID"
echo ""

# Create User 4 (Diana)
echo "4. Creating Diana's profile..."
DIANA_RESPONSE=$(curl -s -X POST $BASE_URL/profile \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Diana",
    "age": 27,
    "gender": "FEMALE",
    "interestedIn": "MALE",
    "ageMin": 25,
    "ageMax": 35,
    "maxDistance": 20,
    "latitude": 37.7649,
    "longitude": -122.4294,
    "bio": "Yoga instructor and foodie 🧘"
  }')

DIANA_ID=$(echo $DIANA_RESPONSE | jq -r '.id')
echo "✓ Diana created with ID: $DIANA_ID"
echo ""

# Wait a moment for data to settle
sleep 1

# Get Alice's feed
echo "5. Getting Alice's feed..."
ALICE_FEED=$(curl -s "$BASE_URL/feed/$ALICE_ID?limit=10")
echo "Alice sees the following potential matches:"
echo $ALICE_FEED | jq -r '.[] | "  - \(.name), \(.age), \(.distance)km away"'
echo ""

# Get Bob's feed
echo "6. Getting Bob's feed..."
BOB_FEED=$(curl -s "$BASE_URL/feed/$BOB_ID?limit=10")
echo "Bob sees the following potential matches:"
echo $BOB_FEED | jq -r '.[] | "  - \(.name), \(.age), \(.distance)km away"'
echo ""

# Alice swipes right on Bob
echo "7. Alice swipes RIGHT on Bob..."
ALICE_SWIPE=$(curl -s -X POST $BASE_URL/swipe/$ALICE_ID/$BOB_ID \
  -H "Content-Type: application/json" \
  -d '{"decision": "RIGHT"}')

MATCHED=$(echo $ALICE_SWIPE | jq -r '.matched')
if [ "$MATCHED" = "true" ]; then
  echo "✓ It's a MATCH! 💕"
else
  echo "✓ Swipe recorded (no match yet)"
fi
echo ""

# Bob swipes right on Alice
echo "8. Bob swipes RIGHT on Alice..."
BOB_SWIPE=$(curl -s -X POST $BASE_URL/swipe/$BOB_ID/$ALICE_ID \
  -H "Content-Type: application/json" \
  -d '{"decision": "RIGHT"}')

MATCHED=$(echo $BOB_SWIPE | jq -r '.matched')
if [ "$MATCHED" = "true" ]; then
  MATCH_ID=$(echo $BOB_SWIPE | jq -r '.matchId')
  echo "✓ It's a MATCH! 💕"
  echo "  Match ID: $MATCH_ID"
else
  echo "✓ Swipe recorded"
fi
echo ""

# Alice swipes left on Charlie
echo "9. Alice swipes LEFT on Charlie (pass)..."
curl -s -X POST $BASE_URL/swipe/$ALICE_ID/$CHARLIE_ID \
  -H "Content-Type: application/json" \
  -d '{"decision": "LEFT"}' > /dev/null
echo "✓ Swipe recorded"
echo ""

# Bob swipes right on Diana
echo "10. Bob swipes RIGHT on Diana..."
curl -s -X POST $BASE_URL/swipe/$BOB_ID/$DIANA_ID \
  -H "Content-Type: application/json" \
  -d '{"decision": "RIGHT"}' > /dev/null
echo "✓ Swipe recorded (no match - Diana hasn't swiped Bob yet)"
echo ""

# Get Alice's matches
echo "11. Getting Alice's matches..."
ALICE_MATCHES=$(curl -s "$BASE_URL/matches/$ALICE_ID")
echo "Alice's matches:"
echo $ALICE_MATCHES | jq -r '.[] | "  - \(.matchedUser.name), matched on \(.createdAt)"'
echo ""

# Get Bob's matches
echo "12. Getting Bob's matches..."
BOB_MATCHES=$(curl -s "$BASE_URL/matches/$BOB_ID")
echo "Bob's matches:"
echo $BOB_MATCHES | jq -r '.[] | "  - \(.matchedUser.name), matched on \(.createdAt)"'
echo ""

echo "=== Demo Complete ==="
echo ""
echo "Summary:"
echo "- Created 4 users (Alice, Bob, Charlie, Diana)"
echo "- Generated feeds based on geospatial proximity and preferences"
echo "- Demonstrated swipe mechanics (right = like, left = pass)"
echo "- Created a match between Alice and Bob (mutual right swipes)"
echo "- Retrieved match lists for users"
echo ""
echo "Visit http://localhost:8080/swagger-ui.html for interactive API docs"
