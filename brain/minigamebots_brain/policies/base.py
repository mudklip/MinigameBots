from ..actions import Action


class Policy:
    """Decides one action per tick from what the AI perceives.

    obs   -- the Observation as a dict (same fields as Observation.java)
    frame -- the 128x128 RGB view: numpy array (size, size, 3), raw bytes, or None
    """

    name = "base"

    def act(self, obs: dict, frame) -> Action:
        raise NotImplementedError

    def status(self) -> dict:
        """Extra numbers shown in the mod's Brain screen."""
        return {}
