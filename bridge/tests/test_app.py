import pytest
from fastapi.testclient import TestClient

from lifeos_bridge.app import PUBLIC_PATHS, create_app


def test_health_needs_no_token_and_reveals_nothing(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_whoami_with_token(client, auth):
    response = client.get("/whoami", headers=auth)
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


@pytest.mark.parametrize(
    "header",
    [
        None,
        "",
        "Bearer",
        "Bearer wrong-token",
        "Basic dXNlcjpwYXNz",
        "bearer-ish test",
    ],
)
def test_bad_or_missing_token_is_rejected(client, header):
    headers = {} if header is None else {"Authorization": header}
    response = client.get("/whoami", headers=headers)
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"


def test_token_prefix_is_not_enough(client, settings):
    response = client.get("/whoami", headers={"Authorization": f"Bearer {settings.token[:-1]}"})
    assert response.status_code == 401


def test_every_route_except_public_ones_requires_the_token(settings):
    """Default deny: enumerate all registered routes, including framework ones."""
    app = create_app(settings)
    client = TestClient(app)
    checked = 0
    for route in app.routes:
        path = getattr(route, "path", None)
        if path is None or path in PUBLIC_PATHS:
            continue
        for method in getattr(route, "methods", None) or {"GET"}:
            response = client.request(method, path.replace("{", "").replace("}", ""))
            assert response.status_code == 401, f"{method} {path} is reachable without a token"
            checked += 1
    assert checked >= 1


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json", "/nope", "/health/extra"])
def test_unknown_and_schema_paths_are_rejected_without_token(client, path):
    assert client.get(path).status_code == 401


def test_schema_endpoints_are_disabled_even_with_token(client, auth):
    for path in ["/docs", "/redoc", "/openapi.json"]:
        assert client.get(path, headers=auth).status_code == 404
