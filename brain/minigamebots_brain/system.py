"""Reports what this PC can do: Python, numpy, PyTorch, GPU. Used by the mod's Setup screen."""

import platform
import sys
import time


def report(benchmark: bool = True) -> dict:
    info = {
        "python": platform.python_version(),
        "platform": platform.platform(),
        "numpy": None,
        "torch": None,
        "cuda": False,
        "device": "cpu",
        "gpu": None,
        "vram_gb": None,
        "compute_capability": None,
        "gpu_supported": None,
        "problems": [],
    }
    try:
        import numpy

        info["numpy"] = numpy.__version__
    except ImportError:
        info["problems"].append("numpy is not installed")
    try:
        import torch
    except ImportError:
        info["problems"].append("PyTorch is not installed")
        return info
    info["torch"] = torch.__version__
    if torch.cuda.is_available():
        props = torch.cuda.get_device_properties(0)
        major, minor = props.major, props.minor
        arch = f"sm_{major}{minor}"
        built_for = torch.cuda.get_arch_list()
        info.update({
            "cuda": True,
            "device": "cuda",
            "gpu": props.name,
            "vram_gb": round(props.total_memory / 1024 ** 3, 1),
            "compute_capability": f"{major}.{minor}",
            # PyTorch only runs on GPUs its build includes kernels for (or a compatible PTX).
            "gpu_supported": arch in built_for or any(a.startswith("compute_") for a in built_for),
        })
        if not info["gpu_supported"]:
            info["problems"].append(
                f"This PyTorch build has no kernels for {props.name} ({arch}); install a build that supports it")
    if benchmark:
        info["matmul_ms"] = _benchmark(torch, info["device"])
    return info


def _benchmark(torch, device: str) -> float | None:
    try:
        a = torch.randn(512, 512, device=device)
        b = torch.randn(512, 512, device=device)
        (a @ b).sum().item()
        start = time.perf_counter()
        for _ in range(10):
            (a @ b).sum().item()
        return round((time.perf_counter() - start) * 100, 3)
    except Exception as e:  # A broken driver shows up here, not at import time.
        return None


if __name__ == "__main__":
    import json

    json.dump(report(), sys.stdout)
