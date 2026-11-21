import os
import tempfile
import unittest
from unittest import mock

from minigamebots_brain.mind import llm


class MindErrorTest(unittest.TestCase):
    def test_empty_errors_get_words_and_advice(self):
        self.assertIn("Not enough memory", llm.describe(MemoryError()))
        self.assertIn("Lite mind", llm.describe(RuntimeError("CUDA out of memory. Tried to allocate 20 MiB")))
        self.assertIn("incomplete", llm.describe(OSError("Error no file named model.safetensors")))
        self.assertTrue(llm.describe(ValueError("odd")).startswith("ValueError: odd"))

    def test_falls_back_to_the_lite_mind_when_the_big_one_does_not_fit(self):
        with tempfile.TemporaryDirectory() as root:
            for name in (llm.DEFAULT_MODEL, llm.LITE_MODEL):
                d = llm.model_dir(root, name)
                os.makedirs(d)
                open(os.path.join(d, "config.json"), "w").write("{}")
                open(os.path.join(d, "model.safetensors"), "w").write("x")
            loaded = []

            class FakeModel:
                def to(self, dev):
                    return self

                def eval(self):
                    return self

            def from_pretrained(d, **kw):
                if "1.5B" in d:
                    raise MemoryError()
                loaded.append(d)
                return FakeModel()

            with mock.patch("transformers.AutoModelForCausalLM.from_pretrained", side_effect=from_pretrained), \
                    mock.patch("transformers.AutoTokenizer.from_pretrained", return_value=object()):
                m = llm.LLM(root, device="cpu")
            self.assertEqual(m.name, llm.LITE_MODEL)
            self.assertTrue(loaded and "0.5B" in loaded[0])


if __name__ == "__main__":
    unittest.main()
