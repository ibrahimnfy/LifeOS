"""LifeOS bridge server."""

from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("lifeos-bridge")
except PackageNotFoundError:  # running from a source tree without install
    __version__ = "0.0.0"
