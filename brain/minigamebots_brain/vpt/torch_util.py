"""Small subset of OpenAI VPT's ``lib/torch_util.py`` (MIT, see LICENSE).

Only the helpers the network needs are kept; distributed, pickle and
subprocess helpers from the original are intentionally left out.
"""
import functools
import inspect

import torch as th
from torch import nn


def store_args(method):
    """Stores provided method args as instance attributes (from VPT minecraft_util)."""
    argspec = inspect.getfullargspec(method)
    defaults = {}
    if argspec.defaults is not None:
        defaults = dict(zip(argspec.args[-len(argspec.defaults):], argspec.defaults))
    if argspec.kwonlydefaults is not None:
        defaults.update(argspec.kwonlydefaults)
    arg_names = argspec.args[1:]

    @functools.wraps(method)
    def wrapper(*positional_args, **keyword_args):
        self = positional_args[0]
        args = defaults.copy()
        for name, value in zip(arg_names, positional_args[1:]):
            args[name] = value
        args.update(keyword_args)
        self.__dict__.update(args)
        return method(*positional_args, **keyword_args)

    return wrapper


def zeros(*args, **kwargs):
    # Initial states are created on the CPU; the attention layer moves them to
    # the input's device before use.
    return th.zeros(*args, **kwargs)


_DTYPES = {
    "float32": th.float32, "float": th.float32,
    "float64": th.float64, "double": th.float64,
    "float16": th.float16, "half": th.float16,
    "uint8": th.uint8, "int8": th.int8, "int16": th.int16, "short": th.int16,
    "int32": th.int32, "int": th.int32, "int64": th.int64, "long": th.int64,
    "bool": th.bool,
}


def parse_dtype(x):
    if isinstance(x, th.dtype):
        return x
    if isinstance(x, str) and x in _DTYPES:
        return _DTYPES[x]
    raise ValueError(f"cannot parse {x!r} as a dtype")


def NormedLinear(*args, scale=1.0, dtype=th.float32, **kwargs):
    """nn.Linear with normalized fan-in init."""
    if parse_dtype(dtype) != th.float32:
        raise ValueError("only float32 layers are supported")
    out = nn.Linear(*args, **kwargs)
    out.weight.data *= scale / out.weight.norm(dim=1, p=2, keepdim=True)
    if kwargs.get("bias", True):
        out.bias.data *= 0
    return out


def LayerNorm(*args, dtype=th.float32, **kwargs):
    if parse_dtype(dtype) != th.float32:
        raise ValueError("only float32 layers are supported")
    out = nn.LayerNorm(*args, **kwargs)
    out.weight.no_scale = True
    return out


def flatten_image(x):
    """Flattens last three dims."""
    *batch_shape, h, w, c = x.shape
    return x.reshape((*batch_shape, h * w * c))


def sequential(layers, x, *args, diag_name=None, use_checkpoint=False):
    for layer in layers:
        x = layer(x, *args)
    return x
