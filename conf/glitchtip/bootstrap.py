"""Bootstrap GlitchTip for OpenSRP: org, projects, DSNs (idempotent)."""
from __future__ import annotations

import os
import re
from pathlib import Path

from django.apps import apps
from django.contrib.auth import get_user_model
from django.db import transaction
from django.utils.text import slugify

OUT = Path(os.environ.get("GLITCHTIP_OUT_DIR", "/out"))
EMAIL = os.environ.get("GLITCHTIP_SUPERUSER_EMAIL", "admin@opensrp.local")
PASSWORD = os.environ.get("GLITCHTIP_SUPERUSER_PASSWORD", "adminadmin")
ORG_SLUG = os.environ.get("GLITCHTIP_ORG_SLUG", "opensrp")
ORG_NAME = os.environ.get("GLITCHTIP_ORG_NAME", "OpenSRP")

# Public / browser / Android / Java DSNs
# With Let's Encrypt IP certs, public HTTPS is trusted by default JVM trust stores
# → HAPI/gateway can use the same public host (no custom truststore).
HOST_GLITCHTIP = os.environ.get("GLITCHTIP_PUBLIC_HOST", "https://localhost/logs")
ANDROID_GLITCHTIP = os.environ.get("GLITCHTIP_ANDROID_HOST", "https://10.0.2.2/logs")
# Optional override for in-network ingest (defaults to public HTTPS host)
INTERNAL_GLITCHTIP = os.environ.get("GLITCHTIP_INTERNAL_HOST") or HOST_GLITCHTIP

# Stable projects (slug, name, platform, dsn file stem)
PROJECTS = [
    ("fhir-core-android", "FHIR Core Android", "android", "android"),
    ("hapi", "HAPI FHIR", "java", "hapi"),
    ("gateway", "FHIR Gateway", "java", "gateway"),
]


def _rewrite_dsn_host(dsn: str, base: str) -> str:
    if "://" not in dsn or "@" not in dsn:
        return dsn
    scheme, rest = dsn.split("://", 1)
    key = rest.split("@", 1)[0]
    after_at = rest.split("@", 1)[1] if "@" in rest else ""
    project_id = after_at.rstrip("/").rsplit("/", 1)[-1] if after_at else ""
    base = base.rstrip("/")
    if "://" in base:
        base_scheme, base_authority = base.split("://", 1)
    else:
        base_scheme, base_authority = scheme, base
    return f"{base_scheme}://{key}@{base_authority}/{project_id}"


def _get_or_create_project(Project, org, slug: str, name: str, platform: str):
    project = Project.objects.filter(organization=org, slug=slug).first()
    if project:
        return project, False
    project = Project.objects.filter(organization=org, name=name).first()
    if project:
        return project, False
    base = slugify(name) or slug
    project = Project.objects.filter(organization=org, slug=base).first()
    if project:
        return project, False
    numbered = (
        Project.objects.filter(organization=org, slug__regex=rf"^{re.escape(base)}(-\d+)?$")
        .order_by("id")
        .first()
    )
    if numbered:
        return numbered, False
    project = Project.objects.create(
        organization=org, slug=slug, name=name, platform=platform
    )
    return project, True


def main() -> None:
    User = get_user_model()
    Organization = apps.get_model("organizations_ext", "Organization")
    OrganizationUser = apps.get_model("organizations_ext", "OrganizationUser")
    OrganizationOwner = apps.get_model("organizations_ext", "OrganizationOwner")
    Project = apps.get_model("projects", "Project")
    ProjectKey = apps.get_model("projects", "ProjectKey")

    OUT.mkdir(parents=True, exist_ok=True)
    dsn_lines: list[str] = []

    with transaction.atomic():
        user, created = User.objects.get_or_create(
            email=EMAIL,
            defaults={"is_staff": True, "is_superuser": True},
        )
        user.set_password(PASSWORD)
        user.is_staff = True
        user.is_superuser = True
        user.save()
        print(f"superuser {'created' if created else 'updated'}: {EMAIL}")

        org, org_created = Organization.objects.get_or_create(
            slug=ORG_SLUG, defaults={"name": ORG_NAME}
        )
        print(f"org {'created' if org_created else 'exists'}: {org.slug}")

        ou, _ = OrganizationUser.objects.update_or_create(
            organization=org,
            user=user,
            defaults={"role": 3, "email": user.email},
        )
        try:
            OrganizationOwner.objects.get_or_create(
                organization=org, organization_user=ou
            )
        except Exception as exc:  # noqa: BLE001
            print(f"owner link skipped: {exc}")

        try:
            Team = apps.get_model("teams", "Team")
            Team.objects.get_or_create(organization=org, slug=ORG_SLUG)
        except Exception as exc:  # noqa: BLE001
            print(f"team skipped: {exc}")

        org.is_accepting_events = True
        org.open_membership = True
        if hasattr(org, "is_active"):
            org.is_active = True
        org.save()

        primary_host_dsn = ""
        primary_android_dsn = ""

        for slug, name, platform, stem in PROJECTS:
            project, project_created = _get_or_create_project(
                Project, org, slug, name, platform
            )
            action = "created" if project_created else "reusing"
            print(f"{action} project id={project.id} slug={project.slug}")

            key = ProjectKey.objects.filter(project=project).first()
            if key is None:
                key = ProjectKey.objects.create(project=project, name="Default")
                print(f"  created project key for {project.slug}")

            dsn = key.get_dsn()
            host_dsn = _rewrite_dsn_host(dsn, HOST_GLITCHTIP)
            android_dsn = _rewrite_dsn_host(dsn, ANDROID_GLITCHTIP)
            # Java services talk to GlitchTip through Traefik HTTPS on the compose network
            internal_dsn = _rewrite_dsn_host(dsn, INTERNAL_GLITCHTIP)

            (OUT / f"public-dsn.{stem}.txt").write_text(host_dsn + "\n", encoding="utf-8")
            (OUT / f"public-dsn.{stem}.android.txt").write_text(
                android_dsn + "\n", encoding="utf-8"
            )
            (OUT / f"public-dsn.{stem}.internal.txt").write_text(
                internal_dsn + "\n", encoding="utf-8"
            )
            print(f"  public DSN:   {host_dsn}")
            print(f"  internal DSN: {internal_dsn}")

            env_key = {
                "android": "SENTRY_DSN_ANDROID",
                "hapi": "SENTRY_DSN_HAPI",
                "gateway": "SENTRY_DSN_GATEWAY",
            }.get(stem, f"SENTRY_DSN_{stem.upper()}")
            # dsn.env: public for docs; internal for compose Java services
            if stem in ("hapi", "gateway"):
                dsn_lines.append(f"{env_key}={internal_dsn}")
            else:
                dsn_lines.append(f"{env_key}={host_dsn}")

            if stem == "android":
                primary_host_dsn = host_dsn
                primary_android_dsn = android_dsn

            if stem == "gateway":
                (OUT / "env.gateway").write_text(
                    f"SENTRY_DSN={internal_dsn}\n"
                    f"SENTRY_ENVIRONMENT=gateway\n"
                    f"SENTRY_RELEASE=opensrp-gateway\n",
                    encoding="utf-8",
                )
            if stem == "hapi":
                # application.yaml uses SENTRY_DSN_HAPI via init-setup envsubst;
                # container also gets SENTRY_DSN for the Java SDK.
                (OUT / "env.hapi").write_text(
                    f"SENTRY_DSN={internal_dsn}\n"
                    f"SENTRY_DSN_HAPI={internal_dsn}\n"
                    f"SENTRY_ENVIRONMENT=hapi\n"
                    f"SENTRY_RELEASE=opensrp-hapi\n",
                    encoding="utf-8",
                )

        # Back-compat filenames used by docs / android local.properties
        if primary_host_dsn:
            (OUT / "public-dsn.host.txt").write_text(primary_host_dsn + "\n", encoding="utf-8")
            (OUT / "public-dsn.txt").write_text(primary_host_dsn + "\n", encoding="utf-8")
        if primary_android_dsn:
            (OUT / "public-dsn.android.txt").write_text(
                primary_android_dsn + "\n", encoding="utf-8"
            )

        (OUT / "dsn.env").write_text("\n".join(dsn_lines) + "\n", encoding="utf-8")


main()
