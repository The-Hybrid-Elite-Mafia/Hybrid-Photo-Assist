"""Runs the real LaMa fill model on a test picture to prove the app's assumptions:
input/output shapes, output value range, and that pixels outside the mask are kept."""
import sys
import numpy as np

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    import tensorflow as tf
    Interpreter = tf.lite.Interpreter

path = sys.argv[1]
it = Interpreter(model_path=path, num_threads=4)
it.allocate_tensors()
ins = it.get_input_details()
outs = it.get_output_details()
print("inputs:", [(d["name"], list(d["shape"]), d["dtype"].__name__) for d in ins])
print("outputs:", [(d["name"], list(d["shape"]), d["dtype"].__name__) for d in outs])
assert len(ins) == 2, "expected 2 inputs"
img_d = next(d for d in ins if d["shape"][-1] == 3)
msk_d = next(d for d in ins if d["shape"][-1] == 1)
s = int(img_d["shape"][1])
assert img_d["shape"][2] == s, "input not square"

yy, xx = np.mgrid[0:s, 0:s]
img = np.stack([xx / s, yy / s, np.full_like(xx, 0.4, dtype=float)], -1).astype(np.float32)[None]
mask = np.zeros((1, s, s, 1), np.float32)
mask[0, s // 3: 2 * s // 3, s // 3: 2 * s // 3, 0] = 1.0
img_holed = img.copy()
img_holed[mask[..., 0] > 0] = 1.0  # bright square that must be painted over

it.set_tensor(img_d["index"], img_holed)
it.set_tensor(msk_d["index"], mask)
it.invoke()
out = it.get_tensor(outs[0]["index"]).astype(np.float32)
print("output range: %.4f .. %.4f" % (out.min(), out.max()))
assert out.shape == (1, s, s, 3), f"unexpected output shape {out.shape}"

# Same normalisation as Inpainter.normalizeOutput
if out.max() > 1.5:
    out = out / 255.0
elif out.min() < -0.05:
    out = (out + 1) / 2
out = np.clip(out, 0, 1)

outside = mask[0, ..., 0] == 0
keep_err = np.abs(out[0][outside] - img_holed[0][outside]).mean()
inside_err = np.abs(out[0][~outside] - img[0][~outside]).mean()
holed_err = np.abs(img_holed[0][~outside] - img[0][~outside]).mean()
print(f"outside mean change: {keep_err:.4f}  inside error vs truth: {inside_err:.4f} (before fill {holed_err:.4f})")
assert keep_err < 0.08, "model changed pixels outside the mask too much"
assert inside_err < holed_err * 0.7, "model did not fill the hole convincingly"
print("FILL MODEL CHECK PASSED")
