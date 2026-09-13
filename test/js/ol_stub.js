function OlStub(opts) {
  this.opts = opts || {};
  this._on = {};
  this.props = {id: 1, name: "北"};
  this._arr = [this];
}

OlStub.prototype.setTarget = function () {};
OlStub.prototype.addInteraction = function () {};
OlStub.prototype.fit = function () {};
OlStub.prototype.getView = function () { return this; };
OlStub.prototype.calculateExtent = function () { return [129, 26, 146, 46]; };
OlStub.prototype.forEachFeatureAtPixel = function (_px, fn) { fn(this); };
OlStub.prototype.readFeature = function () { return this; };
OlStub.prototype.writeGeometryObject = function () { return {type: "Polygon", coordinates: []}; };
OlStub.prototype.getGeometry = function () { return this; };
OlStub.prototype.getArray = function () { return this._arr; };
OlStub.prototype.get = function (k) { return this.props[k]; };
OlStub.prototype.push = function () {};
OlStub.prototype.on = function (ev, fn) {
  this._on[ev] = fn;
  if (typeof global !== "undefined") {
    global.__olLast = global.__olLast || {};
    global.__olLast[ev] = this;
  }
  return this;
};

OlStub.default = OlStub;
module.exports = OlStub;
