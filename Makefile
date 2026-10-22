# Slopstream. Everything runs in Docker; no host toolchain needed.
#
#   make test         JVM tests: core policy, YouTube parsers, app Robolectric tests (offline)
#   make smoke        live checks against YouTube (network): feeds, Shorts split, stream plan
#   make smoke-floatplane  read-only report from your Floatplane account (FP_CLIENT_ID,
#                     FP_BEARER or FP_SESSION in the environment; see FloatplaneSmokeTest)
#   make build        debug APK -> build-output/slopstream-debug.apk
#   make release      R8-shrunk release APK (signed when keystore.properties exists)
#   make lint         Android lint
#   make screenshots  render the main screens (phone + TV) -> build-output/screenshots/
#   make keystore     create the release signing key (once)
#   make install      adb install the debug APK

COMPOSE = docker compose -f docker/docker-compose.yml

.PHONY: test smoke smoke-floatplane build release lint screenshots keystore install

test:
	$(COMPOSE) run --rm --build test

smoke:
	$(COMPOSE) run --rm --build smoke

smoke-floatplane:
	$(COMPOSE) run --rm --build smoke-floatplane

build:
	$(COMPOSE) run --rm --build build

release:
	$(COMPOSE) run --rm --build build-release

lint:
	$(COMPOSE) run --rm --build lint

screenshots:
	$(COMPOSE) run --rm --build screenshots

keystore:
	$(COMPOSE) run --rm --build keystore

install:
	adb install -r build-output/slopstream-debug.apk
