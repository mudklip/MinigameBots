"""OpenAI's VPT ("Video PreTraining") Minecraft foundation model, ported without gym/MineRL.

The network code in this folder comes from https://github.com/openai/Video-Pre-Training
under the MIT license (see LICENSE here). Our changes: relative imports, a trimmed
torch_util, model sizes written out instead of unpickled, and plain action heads.
"""
